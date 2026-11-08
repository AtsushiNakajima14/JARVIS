$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
try {
    foreach ($sample in @(@('wake', 'Jarvis'), @('negative', 'battery percentage'), @('command', 'open discord'))) {
        $synth.SetOutputToWaveFile("$projectRoot/.tooling/$($sample[0])-test.wav", $format)
        $synth.Speak($sample[1])
        $synth.SetOutputToNull()
    }
} finally { $synth.Dispose() }
python "$PSScriptRoot/model_smoke.py"
if ($LASTEXITCODE -ne 0) { throw 'Offline model smoke check failed' }

