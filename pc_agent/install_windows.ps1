<#
.SYNOPSIS
    Installs psutil and registers Smart Desk PC Monitor (pc_monitor.py) in Windows Task Scheduler
    to run silently at user logon via pythonw.exe (no console window).
#>

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonScript = Join-Path $ScriptDir "pc_monitor.py"
$TaskName = "SmartDeskPCMonitor"

Write-Host "1. Installing/verifying psutil..." -ForegroundColor Cyan
python -m pip install --upgrade psutil

$PythonExe = (Get-Command python).Source
$PythonwExe = Join-Path (Split-Path -Parent $PythonExe) "pythonw.exe"
if (-not (Test-Path $PythonwExe)) {
    $PythonwExe = $PythonExe
}

Write-Host "2. Registering Windows Task Scheduler task '$TaskName' (At log on, hidden window)..." -ForegroundColor Cyan
$Action = New-ScheduledTaskAction -Execute $PythonwExe -Argument "`"$PythonScript`"" -WorkingDirectory $ScriptDir
$Trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$Settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $Action `
    -Trigger $Trigger `
    -Settings $Settings `
    -Description "Smart Desk Companion V6 Laptop Resource Monitor (pushes CPU/RAM/Temp to Firebase)" `
    -Force | Out-Null

Start-ScheduledTask -TaskName $TaskName
Write-Host "Done! '$TaskName' is now running in the background and will start automatically at login." -ForegroundColor Green
Write-Host "Remember to set your Firebase 'databaseUrl' in: $ScriptDir\config.json" -ForegroundColor Yellow
