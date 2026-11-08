param([string[]]$Tasks = @(':assistant:parser:test', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkRoot = Get-ChildItem -LiteralPath "$projectRoot/.tooling/jdk" -Directory | Select-Object -First 1
if (-not $jdkRoot) { throw 'Run python scripts/bootstrap.py first, or build in Android Studio with JDK 17+.' }
$env:JAVA_HOME = $jdkRoot.FullName
$env:ANDROID_HOME = "$projectRoot/.tooling/android-sdk"
$env:GRADLE_USER_HOME = "$projectRoot/.tooling/gradle-user-home"
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:DEBUG = ''
$gradle = "$projectRoot/.tooling/gradle/gradle-9.3.1/bin/gradle.bat"
Push-Location $projectRoot
try { & $gradle @Tasks --console=plain; if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" } }
finally { Pop-Location }
