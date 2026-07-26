param(
    [string]$TaskName = "Fcitx5 Android AI Companion"
)

$ErrorActionPreference = "Stop"
$launcherPath = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot "start-ai-provider-companion.ps1"
)).Path
$powershell = (Get-Command powershell.exe -ErrorAction Stop).Source
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f $launcherPath

$action = New-ScheduledTaskAction -Execute $powershell -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
$principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable

Register-ScheduledTask -TaskName $TaskName -Description "Connect logged-in Codex and Claude Code to Fcitx Android" -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

Start-ScheduledTask -TaskName $TaskName
Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State
