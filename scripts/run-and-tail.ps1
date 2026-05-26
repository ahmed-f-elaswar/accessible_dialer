# Build + install debug APK on the connected device, then start filtered logcat
# tailing the app PID. Use --tail-only to skip the build step.
param([switch]$TailOnly)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

if (-not $TailOnly) {
    Write-Host "==> Building + installing debug APK..." -ForegroundColor Cyan
    cmd /c ".\gradlew.bat installDebug --console=plain"
    if ($LASTEXITCODE -ne 0) { throw "Build/install failed (exit $LASTEXITCODE)" }
}

Write-Host "==> Launching com.accessible.dialer/.MainActivity..." -ForegroundColor Cyan
& $adb logcat -c
& $adb shell am start -n com.accessible.dialer/.MainActivity | Out-Null
Start-Sleep -Milliseconds 1500
$pid_ = (& $adb shell pidof com.accessible.dialer).Trim()
Write-Host "App PID: $pid_" -ForegroundColor Green

New-Item -ItemType Directory -Force -Path logs | Out-Null
$logFile = Join-Path $root "logs\app.log"
Write-Host "==> Streaming logs to $logFile (Ctrl+C to stop)" -ForegroundColor Cyan
& $adb logcat -v threadtime --pid=$pid_ *:V AndroidRuntime:E ActivityManager:I Telecom:V InCallController:V |
    Tee-Object -FilePath $logFile
