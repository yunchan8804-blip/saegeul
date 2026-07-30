# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

param(
    [string]$TaskName = "Fcitx5 Android AI Companion"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$project = Join-Path $repoRoot "tools\FcitxAiCompanionTray\FcitxAiCompanionTray.csproj"
$publishDirectory = Join-Path $env:LOCALAPPDATA "Fcitx5Android\tray"
$companionPath = Join-Path $repoRoot "scripts\ai-provider-companion.py"

dotnet publish $project -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o $publishDirectory
if ($LASTEXITCODE -ne 0) {
    throw "WPF tray publish failed."
}

$trayExecutable = Join-Path $publishDirectory "FcitxAiCompanionTray.exe"
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$arguments = '--companion "{0}" --gateway-port 9211 --tailscale-https-port 9210' -f $companionPath
$action = New-ScheduledTaskAction -Execute $trayExecutable -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
$principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable

Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Description "Tray manager for the Fcitx Android Codex and Claude gateway" -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State
