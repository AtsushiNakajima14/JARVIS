"""Synthetic readiness-tone and stale pre-roll regression probes.

Generate baseline fixtures with model-smoke.ps1, then run this file with the
exported production --grammar-file. Uses the production decoder configuration:
free N-best=5; grammar single-best + word confidences, accumulated until the free
decoder ends. Android's Kotlin parser/selection and actual microphone are not run.
"""
from array import array
from collections import deque
from pathlib import Path
import argparse
import json
import math
import re
import wave

from model_smoke import ROOT, FIXTURES, SAMPLE_RATE, FRAME_BYTES, WAKE_GRAMMAR, best_text, candidates, without_wake, word_confidence
from vosk import Model, KaldiRecognizer, SetLogLevel


def raw_audio(path):
    with wave.open(str(path), 'rb') as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (16000, 1, 2)
        return wav.readframes(wav.getnframes())


def trim_quiet(pcm):
    samples = array('h', pcm)
    loud = [i for i, value in enumerate(samples) if abs(value) > 20]
    return samples[loud[0]:loud[-1] + 1].tobytes() if loud else b''


def silence(milliseconds):
    return bytes(int(SAMPLE_RATE * milliseconds / 1000) * 2)


def readiness_tone():
    count = int(SAMPLE_RATE * 0.080)
    ramp = int(SAMPLE_RATE * 0.005)
    samples = array('h', (int(8191 * min(1, i / ramp, (count - 1 - i) / ramp)
                             * math.sin(2 * math.pi * 1000 * i / SAMPLE_RATE)) for i in range(count)))
    return samples.tobytes()


def write_wave(path, pcm):
    with wave.open(str(path), 'wb') as wav:
        wav.setparams((1, 2, SAMPLE_RATE, 0, 'NONE', 'not compressed'))
        wav.writeframes(pcm)


def trim_to_wake(result):
    def trim_text(text):
        match = re.search(r'\bjarvis\b', text, re.I)
        return text[match.start():] if match else text
    if 'alternatives' in result:
        return {**result, 'alternatives': [{**candidate, 'text': trim_text(candidate.get('text', ''))}
                                          for candidate in result['alternatives']]}
    words = result.get('result', [])
    index = next((i for i, word in enumerate(words) if word.get('word', '').lower() == 'jarvis'), None)
    return {**result, 'text': trim_text(result.get('text', '')),
            'result': words[index:] if index is not None else words}


class Pair:
    """Match OfflineCommandDecoder's endpoints and grammar segment accumulation."""
    def __init__(self, model, grammar, from_wake=False):
        self.free = KaldiRecognizer(model, SAMPLE_RATE)
        self.free.SetMaxAlternatives(5)
        self.grammar = KaldiRecognizer(model, SAMPLE_RATE, json.dumps(grammar))
        self.grammar.SetWords(True)
        self.parts = []
        self.from_wake = from_wake

    def accept(self, frame):
        completed = self.free.AcceptWaveform(frame)
        if self.grammar.AcceptWaveform(frame):
            self.parts.append(json.loads(self.grammar.Result()))
        return self._finish(json.loads(self.free.Result())) if completed else None

    def _finish(self, free):
        self.parts.append(json.loads(self.grammar.FinalResult()))
        joined = {'text': ' '.join(part.get('text', '') for part in self.parts).strip(),
                  'result': [word for part in self.parts for word in part.get('result', [])]}
        self.parts.clear()
        return {'free': trim_to_wake(free) if self.from_wake else free,
                'constrained': trim_to_wake(joined) if self.from_wake else joined,
                'raw_free': free, 'raw_constrained': joined}

    def finish(self):
        return self._finish(json.loads(self.free.FinalResult()))


def terminal(pair, pcm):
    for offset in range(0, len(pcm), FRAME_BYTES):
        result = pair.accept(pcm[offset:offset + FRAME_BYTES])
        if result is not None and (best_text(result['free']) or best_text(result['constrained'])):
            return result
    return pair.finish()


def leading_wake(result):
    texts = [candidate.get('text', '') for candidate in candidates(result['free'])]
    texts.append(best_text(result['constrained']))
    return any(re.match(r'^(?:(?:hey|hi|ok|okay)\s+)?jarvis\b', text, re.I) for text in texts)


def handoff(model, pcm, grammar, legacy_replay=False):
    wake = KaldiRecognizer(model, SAMPLE_RATE, WAKE_GRAMMAR)
    roll = deque(maxlen=8)
    pair = None
    stable = 0
    replay_completed = []
    detected_at = None
    for offset in range(0, len(pcm), FRAME_BYTES):
        frame = pcm[offset:offset + FRAME_BYTES]
        if pair is None:
            roll.append(frame)
            complete = wake.AcceptWaveform(frame)
            result = json.loads(wake.Result() if complete else wake.PartialResult())
            text = result.get('text' if complete else 'partial', '')
            has_wake = bool(re.search(r'\bjarvis\b', text))
            stable = stable + 1 if has_wake else 0
            if not (stable >= 3 or (complete and text.strip() == 'jarvis')):
                continue
            detected_at = (offset + len(frame)) / (SAMPLE_RATE * 2)
            pair = Pair(model, grammar, from_wake=True)
            last = None
            for retained in roll:
                last = pair.accept(retained)
                if last is not None:
                    replay_completed.append(last)
            roll.clear()
            replay_result = replay_completed[-1] if legacy_replay and replay_completed else last
            if replay_result is not None and (legacy_replay or leading_wake(replay_result)):
                return {**replay_result, 'used_replay_result': True, 'replay_completed': replay_completed,
                        'detected_at_seconds': detected_at}
            continue
        result = pair.accept(frame)
        if result is not None:
            return {**result, 'used_replay_result': False, 'replay_completed': replay_completed,
                    'detected_at_seconds': detected_at}
    if pair is None:
        return None
    return {**pair.finish(), 'used_replay_result': False, 'replay_completed': replay_completed,
            'detected_at_seconds': detected_at}


def expected_available(result, expected, allowed):
    free = [without_wake(candidate.get('text', '')) for candidate in candidates(result['free'])]
    constrained = result['constrained']
    text = without_wake(best_text(constrained))
    values = [word.get('conf', float('nan')) for word in constrained.get('result', [])]
    eligible = (text in allowed and '[unk]' not in text and values
                and all(math.isfinite(value) and 0.55 <= value <= 1 for value in values)
                and sum(values) / len(values) >= 0.78)
    return expected in free or (eligible and text == expected), bool(eligible)


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--grammar-file', type=Path, required=True)
    args.add_argument('--export-regressions', action='store_true')
    config = args.parse_args()
    grammar = json.loads(config.grammar_file.read_text(encoding='utf-8-sig'))
    allowed = {without_wake(phrase) for phrase in grammar if phrase not in {'jarvis', '[unk]'}}
    manifest = json.loads((FIXTURES / 'manifest.json').read_text(encoding='utf-8-sig'))
    # Keep all prior 66 cases intact; use their two normal-rate voices for new edges.
    voices = sorted({sample['voice'] for sample in manifest})
    samples = []
    for voice_id, voice in enumerate(voices):
        by_name = {sample['name']: sample for sample in manifest if sample['voice'] == voice and sample['rate'] == 0}
        if not by_name:
            raise SystemExit('Generate rate 0 fixtures before running readiness/pre-roll probes.')
        for gap in (0, 150):
            for name, expected in [('battery', 'battery percentage'), ('discord', 'open discord')]:
                pcm = readiness_tone() + silence(gap) + trim_quiet(raw_audio(FIXTURES / by_name[name]['file']))
                samples.append({'name': f'voice-{voice_id}-tone-{gap}-{name}', 'kind': 'tone', 'voice': voice,
                                'expected': expected, 'pcm': pcm})
        for gap in (250, 500, 750):
            for earlier, current in [('battery', 'combined-discord'), ('discord', 'combined-battery'),
                                      ('unrelated', 'combined-discord'), ('near-discord', 'combined-battery')]:
                pcm = (trim_quiet(raw_audio(FIXTURES / by_name[earlier]['file'])) + silence(gap)
                       + trim_quiet(raw_audio(FIXTURES / by_name[current]['file'])))
                samples.append({'name': f'voice-{voice_id}-history-{gap}-{earlier}-{current}', 'kind': 'history',
                                'voice': voice, 'expected': by_name[current]['expected_command'], 'pcm': pcm})
    SetLogLevel(-1)
    model = Model(str(ROOT / 'app/src/main/assets/model-en-us'))
    rows, failures = [], []
    for sample in samples:
        pcm = sample.pop('pcm') + silence(2000)
        fixture = FIXTURES / (sample['name'] + '.wav')
        write_wave(fixture, pcm)
        if sample['kind'] == 'tone':
            result = terminal(Pair(model, grammar), pcm)
            legacy = None
        else:
            result = handoff(model, pcm, grammar)
            legacy = handoff(model, pcm, grammar, legacy_replay=True)
        if result is None:
            failures.append(f"{sample['name']}: no wake activation")
            okay, eligible = False, False
        else:
            okay, eligible = expected_available(result, sample['expected'], allowed)
            if not okay:
                failures.append(f"{sample['name']}: expected {sample['expected']!r}, free={best_text(result['free'])!r}, grammar={best_text(result['constrained'])!r}")
        rows.append({**sample, 'result': result, 'legacy_replay': legacy, 'expected_available': okay,
                     'grammar_eligible': eligible})
        print(f"{'PASS' if okay else 'FAIL'} {sample['name']}: free={best_text(result['free']) if result else None!r}, grammar={best_text(result['constrained']) if result else None!r}", flush=True)
    report = {'runtime': 'Windows Vosk 0.3.45; Android uses 0.3.75',
              'decoder_config': 'free N-best5, grammar single-best/words; grammar segments accumulated until free endpoint',
              'selection_limit': 'Checks expected alternatives/exact generated grammar and confidence; Kotlin parser/selection not executed',
              'tone': '80ms 1000Hz sine, amplitude0.25 with5ms fade; gap0 or150ms',
              'replay_policy': 'Use only final replay frame result if leading Jarvis; ignore earlier replay endpoints; trim text/confidences before first wake Jarvis',
              'samples': rows, 'failures': failures}
    (FIXTURES / 'edge-results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    if config.export_regressions:
        cases = []
        picks = [
            ('voice-0-tone-0-battery', 'BATTERY_STATUS', ''),
            ('voice-1-tone-150-discord', 'OPEN_APPLICATION', 'discord'),
            ('voice-0-history-250-battery-combined-discord', 'OPEN_APPLICATION', 'discord'),
            ('voice-0-history-250-discord-combined-battery', 'BATTERY_STATUS', ''),
            ('voice-0-history-250-unrelated-combined-discord', 'OPEN_APPLICATION', 'discord'),
        ]
        for name, intent, argument in picks:
            row = next(row for row in rows if row['name'] == name)
            result = row['result']
            cases.append({'name': name, 'syntheticSource': {'voice': row['voice'], 'rate': 0, 'description': row['kind']},
                          'fromWake': row['kind'] == 'history', 'free': result['raw_free'],
                          'grammar': result['raw_constrained'], 'expectedType': intent, 'expectedArgument': argument})
        base = {sample['name']: sample for sample in manifest if sample['voice'] == voices[0] and sample['rate'] == 0}
        for name, intent, argument in [('near-discord', 'UNKNOWN', 'this guy'), ('memory', 'REMEMBER', 'i use discord for school'), ('cancel', 'CANCEL', '')]:
            source = base[name]
            pcm = raw_audio(FIXTURES / source['file']) + silence(2000)
            result = terminal(Pair(model, grammar), pcm)
            cases.append({'name': name, 'syntheticSource': {'voice': source['voice'], 'rate': 0, 'description': source['name']},
                          'fromWake': False, 'free': result['raw_free'], 'grammar': result['raw_constrained'],
                          'expectedType': intent, 'expectedArgument': argument})
        target = ROOT / 'app/src/test/resources/speech/native-regressions.json'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({'runtime': 'Windows Vosk 0.3.45; synthetic audio only',
                                     'appLabels': ['Discord', 'Facebook', 'YouTube', 'Spotify', 'Google Chrome'],
                                     'cases': cases}, indent=2) + '\n', encoding='utf-8')
        print('Exported', len(cases), 'actual native regression result pairs to', target)
    print(f'{len(rows) - len(failures)}/{len(rows)} edge probes passed; baseline result files preserved.')
    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
