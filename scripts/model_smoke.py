"""Synthetic native-model regression checks; these do not test an Android microphone.

Setup: python -m pip install --target .tooling/python vosk==0.3.45
Run scripts/model-smoke.ps1 on Windows with local English TTS voices installed.
An optional --grammar-file JSON array supplies the exact production phrase list.
Generated speech, decoded text and results remain in ignored .tooling/.
"""
from collections import deque
from pathlib import Path
import argparse
import json
import re
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tooling/python'))
from vosk import Model, KaldiRecognizer, SetLogLevel

SAMPLE_RATE = 16000
FRAME_BYTES = 3200 * 2
FIXTURES = ROOT / '.tooling/native-voice-tests'
WAKE_GRAMMAR = '["jarvis", "[unk]"]'
SAFE_COMMANDS = [
    'battery percentage', 'battery status', 'what is my battery percentage',
    'turn on flashlight', 'turn off flashlight', 'increase volume', 'decrease volume',
    'open camera', 'show device information', 'show installed apps',
    'check storage', 'check memory usage', 'pause music', 'play music',
    'next song', 'previous song', 'set a timer for five minutes',
] + [f'{verb} {app}' for app in ['discord', 'facebook', 'you tube', 'spotify', 'google chrome'] for verb in ['open', 'launch', 'start']]


def candidates(result):
    return result.get('alternatives') or [result]


def without_wake(text):
    return re.sub(r'^(?:(?:hey|hi|okay|ok)\s+)?jarvis\b[\s,.:;!?-]*', '', text, flags=re.I).strip()


def best_text(result):
    return candidates(result)[0].get('text', '')


def word_confidence(candidate):
    values = [word['conf'] for word in candidate.get('result', []) if 'conf' in word]
    return sum(values) / len(values) if values else None


def load_audio(path):
    with wave.open(str(path), 'rb') as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (SAMPLE_RATE, 1, 2)
        return wav.readframes(wav.getnframes()) + bytes(SAMPLE_RATE * 2 * 2)


def decoder(model, grammar=None):
    rec = KaldiRecognizer(model, SAMPLE_RATE, json.dumps(grammar)) if grammar else KaldiRecognizer(model, SAMPLE_RATE)
    rec.SetWords(True)
    rec.SetMaxAlternatives(3)
    return rec


def transcribe(model, pcm, grammar=None):
    rec = decoder(model, grammar)
    completed = []
    for offset in range(0, len(pcm), FRAME_BYTES):
        if rec.AcceptWaveform(pcm[offset:offset + FRAME_BYTES]):
            completed.append(json.loads(rec.Result()))
    final = json.loads(rec.FinalResult())
    if best_text(final):
        completed.append(final)
    return completed[0] if completed else final


def detect_and_handoff(model, pcm, grammar):
    """Replay 1.6 s of PCM at wake, then process the very same audio stream."""
    wake = KaldiRecognizer(model, SAMPLE_RATE, WAKE_GRAMMAR)
    roll = deque(maxlen=8)
    full = limited = None
    detected_at = None
    segments = []
    stable_wake_frames = 0
    for offset in range(0, len(pcm), FRAME_BYTES):
        frame = pcm[offset:offset + FRAME_BYTES]
        if full is None:
            roll.append(frame)
            complete = wake.AcceptWaveform(frame)
            result = json.loads(wake.Result() if complete else wake.PartialResult())
            text = result.get('text' if complete else 'partial', '')
            has_wake = bool(re.search(r'\bjarvis\b', text))
            stable_wake_frames = stable_wake_frames + 1 if has_wake else 0
            # One or two partials can be transient near-homophone mistakes.
            if not has_wake or (not complete and stable_wake_frames < 3):
                continue
            detected_at = (offset + len(frame)) / (SAMPLE_RATE * 2)
            full, limited = decoder(model), decoder(model, grammar)
            for retained in roll:
                full.AcceptWaveform(retained)
                limited.AcceptWaveform(retained)
            roll.clear()
            continue
        free_complete = full.AcceptWaveform(frame)
        limited_complete = limited.AcceptWaveform(frame)
        if free_complete or limited_complete:
            free = json.loads(full.Result() if free_complete else full.FinalResult())
            constrained = json.loads(limited.Result() if limited_complete else limited.FinalResult())
            segments.append((free, constrained))
            break
    if full is None:
        return None
    if not segments:
        segments.append((json.loads(full.FinalResult()), json.loads(limited.FinalResult())))
    free, constrained = segments[0]
    return {'detected_at_seconds': detected_at, 'free': free, 'constrained': constrained}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--grammar-file', type=Path)
    args = parser.parse_args()
    grammar = json.loads(args.grammar_file.read_text(encoding='utf-8-sig')) if args.grammar_file else list(dict.fromkeys(
        ['jarvis', '[unk]'] + SAFE_COMMANDS + ['jarvis ' + phrase for phrase in SAFE_COMMANDS]))
    SetLogLevel(-1)
    model = Model(str(ROOT / 'app/src/main/assets/model-en-us'))
    manifest = json.loads((FIXTURES / 'manifest.json').read_text(encoding='utf-8-sig'))
    executable_phrases = {without_wake(phrase) for phrase in grammar if phrase not in {'jarvis', '[unk]'}}
    rows = []
    failures = []
    for sample in manifest:
        pcm = load_audio(FIXTURES / sample['file'])
        free = transcribe(model, pcm)
        limited = transcribe(model, pcm, grammar)
        handoff = detect_and_handoff(model, pcm, grammar)
        row = {**sample, 'free': free, 'constrained': limited, 'handoff': handoff}
        rows.append(row)
        text = without_wake(best_text(limited))
        expected = sample['expected_command']
        if expected and text != expected:
            failures.append(f"{sample['file']}: constrained {text!r}, expected {expected!r}")
        if sample['expected_wake']:
            if handoff is None:
                failures.append(f"{sample['file']}: wake was missed")
            elif expected and without_wake(best_text(handoff['constrained'])) != expected:
                failures.append(f"{sample['file']}: command clipped during handoff: {best_text(handoff['constrained'])!r}")
            elif not expected and without_wake(best_text(handoff['constrained'])):
                failures.append(f"{sample['file']}: bare wake word became a command")
        elif handoff is not None:
            failures.append(f"{sample['file']}: unrelated utterance triggered the wake word")
        if not expected and sample['name'] not in {'wake', 'memory'}:
            if text in executable_phrases:
                failures.append(f"{sample['file']}: unrelated speech forced a device phrase: {text!r}")
        if sample['name'] == 'memory' and best_text(free) != 'remember that i use discord for school':
            failures.append(f"{sample['file']}: free-form memory content was not preserved")
        if sample['name'] == 'cancel' and best_text(free) != 'cancel':
            failures.append(f"{sample['file']}: cancellation was not preserved")
        top = candidates(limited)[0]
        print(f"{sample['voice']} / {sample['name']}: free={best_text(free)!r}, constrained={best_text(limited)!r}, word confidence={word_confidence(top)}")
    report = {'runtime': 'Windows Vosk 0.3.45', 'synthetic_voices': sorted({s['voice'] for s in manifest}),
              'grammar': str(args.grammar_file) if args.grammar_file else 'representative safe-command fixture grammar',
              'samples': rows, 'failures': failures}
    (FIXTURES / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    if failures:
        for failure in failures:
            print('FAIL', failure)
        raise SystemExit(1)
    print(f'PASS: {len(rows)} native synthetic speech cases; continuous/pre-roll handoff and negative wake cases checked.')
    print('Actual Android microphone/accent/OEM behavior remains untested by this script.')


if __name__ == '__main__':
    main()
