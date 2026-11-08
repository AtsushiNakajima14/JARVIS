param([int[]]$Rates = @(0), [string]$GrammarFile = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$fixtureRoot = Join-Path $projectRoot '.tooling/native-voice-tests'
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$manifest = @()
$samples = @(
    @{ Name = 'wake'; Text = 'Jarvis'; Command = ''; Wake = $true },
    @{ Name = 'battery'; Text = 'battery percentage'; Command = 'battery percentage'; Wake = $false },
    @{ Name = 'discord'; Text = 'open Discord'; Command = 'open discord'; Wake = $false },
    @{ Name = 'combined-battery'; Text = 'Jarvis battery percentage'; Command = 'battery percentage'; Wake = $true },
    @{ Name = 'combined-discord'; Text = 'Jarvis open Discord'; Command = 'open discord'; Wake = $true },
    @{ Name = 'paused-battery'; Text = 'battery percentage'; Prefix = 'Jarvis'; Pause = 200; Command = 'battery percentage'; Wake = $true },
    @{ Name = 'paused-discord'; Text = 'open Discord'; Prefix = 'Jarvis'; Pause = 250; Command = 'open discord'; Wake = $true },
    @{ Name = 'unrelated'; Text = 'The weather is lovely today'; Command = ''; Wake = $false },
    @{ Name = 'near-discord'; Text = 'this guy'; Command = ''; Wake = $false },
    @{ Name = 'cancel'; Text = 'cancel'; Command = ''; Wake = $false },
    @{ Name = 'memory'; Text = 'remember that I use Discord for school'; Command = ''; Wake = $false }
)
try {
    $voiceNumber = 0
    $voices = $synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'en-US' }
    if (-not $voices) { throw 'Install a local English Windows TTS voice for this optional smoke test.' }
    foreach ($voice in $voices) {
        $synth.SelectVoice($voice.VoiceInfo.Name)
        foreach ($rate in $Rates) {
            $synth.Rate = $rate
            foreach ($sample in $samples) {
                $fileName = "voice-$voiceNumber-rate-$rate-$($sample.Name).wav"
                $synth.SetOutputToWaveFile((Join-Path $fixtureRoot $fileName), $format)
                $prompt = New-Object System.Speech.Synthesis.PromptBuilder
                if ($sample.ContainsKey('Prefix')) {
                    $prompt.AppendText($sample.Prefix)
                    $prompt.AppendBreak([TimeSpan]::FromMilliseconds($sample.Pause))
                }
                $prompt.AppendText($sample.Text)
                $synth.Speak($prompt)
                $synth.SetOutputToNull()
                $manifest += @{ file = $fileName; name = $sample.Name; voice = $voice.VoiceInfo.Name; rate = $rate; expected_command = $sample.Command; expected_wake = $sample.Wake }
            }
        }
        $voiceNumber++
    }
} finally { $synth.Dispose() }
[System.IO.File]::WriteAllText((Join-Path $fixtureRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
if ($GrammarFile) { python -u "$PSScriptRoot/model_smoke.py" --grammar-file $GrammarFile }
else { python -u "$PSScriptRoot/model_smoke.py" }
if ($LASTEXITCODE -ne 0) { throw 'Offline model smoke check failed' }

