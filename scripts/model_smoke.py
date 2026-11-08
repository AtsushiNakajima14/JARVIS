"""Optional synthetic-audio check, not a substitute for an Android microphone test.

Setup: python -m pip install --target .tooling/python vosk==0.3.45
Run scripts/model-smoke.ps1 on Windows with an installed system TTS voice.
"""
from pathlib import Path
import json
import sys
import wave

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / '.tooling/python'))
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
model = Model(str(root / 'app/src/main/assets/model-en-us'))
for name in ['wake', 'negative', 'command']:
    recognizer = KaldiRecognizer(model, 16000) if name == 'command' else KaldiRecognizer(model, 16000, '["jarvis", "[unk]"]')
    with wave.open(str(root / f'.tooling/{name}-test.wav'), 'rb') as wav:
        recognizer.AcceptWaveform(wav.readframes(wav.getnframes()))
    result = json.loads(recognizer.FinalResult())['text']
    if name == 'wake':
        assert result == 'jarvis', result
    elif name == 'negative':
        assert 'jarvis' not in result, result
    else:
        assert result == 'open discord', result
    print(f'PASS {name}: {result}')
