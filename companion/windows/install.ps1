# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

param(
    [string]$TaskName = "Saegeul AI Companion",
    [string]$InstallDir = "$env:LOCALAPPDATA\Saegeul\companion",
    [int]$GatewayPort = 9211,
    [int]$TailscaleHttpsPort = 9210,
    [switch]$BuildWpfTray
)

$ErrorActionPreference = "Stop"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Saegeul AI Companion Windows Setup" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}

$repoRoot = $null
try {
    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..") -ErrorAction Stop).Path
} catch {}

$sourceScript = $null
if ($repoRoot -and (Test-Path (Join-Path $repoRoot "scripts\ai-provider-companion.py"))) {
    $sourceScript = Join-Path $repoRoot "scripts\ai-provider-companion.py"
} elseif (Test-Path (Join-Path $PSScriptRoot "ai-provider-companion.py")) {
    $sourceScript = Join-Path $PSScriptRoot "ai-provider-companion.py"
} elseif (Test-Path (Join-Path $InstallDir "ai-provider-companion.py")) {
    $sourceScript = Join-Path $InstallDir "ai-provider-companion.py"
}

if (-not $sourceScript) {
    throw "Could not locate ai-provider-companion.py"
}

$targetCompanion = Join-Path $InstallDir "ai-provider-companion.py"
if ($sourceScript -ne $targetCompanion) {
    Copy-Item -Path $sourceScript -Destination $targetCompanion -Force
}

$pythonCmd = Get-Command python.exe -ErrorAction SilentlyContinue
$pythonwCmd = Get-Command pythonw.exe -ErrorAction SilentlyContinue
$pythonExe = if ($pythonCmd) { $pythonCmd.Source } else { $null }
$pythonwExe = if ($pythonwCmd) { $pythonwCmd.Source } else { $null }
$executable = $pythonwExe
if (-not $executable) {
    $executable = $pythonExe
}

# Optional WPF Tray build if .NET SDK is available
$trayProject = if ($repoRoot) { Join-Path $repoRoot "tools\SaegeulAiCompanionTray\SaegeulAiCompanionTray.csproj" } else { $null }
if ($BuildWpfTray -and $trayProject -and (Test-Path $trayProject) -and (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    Write-Host "[*] Building WPF System Tray application..." -ForegroundColor Yellow
    Stop-Process -Name "SaegeulAiCompanionTray" -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    $trayPublishDir = Join-Path $InstallDir "tray"
    dotnet publish $trayProject -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o $trayPublishDir
    if ($LASTEXITCODE -eq 0) {
        $trayExe = Join-Path $trayPublishDir "SaegeulAiCompanionTray.exe"
        if (Test-Path $trayExe) {
            $executable = $trayExe
            $arguments = '--companion "{0}" --gateway-port {1} --tailscale-https-port {2}' -f $targetCompanion, $GatewayPort, $TailscaleHttpsPort
        }
    }
}

if (-not $arguments) {
    if (-not $executable) {
        throw "Python 3 is required to run Saegeul AI Companion. Please install Python from https://www.python.org or Microsoft Store."
    }
    $arguments = '"{0}" --gateway-port {1} --tailscale-https-port {2}' -f $targetCompanion, $GatewayPort, $TailscaleHttpsPort
}

$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $executable -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
$principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable

Write-Host "[*] Registering Scheduled Task: $TaskName" -ForegroundColor Green
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Description "Background daemon & gateway for Saegeul AI Companion" -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

Write-Host "[*] Starting Saegeul AI Companion service..." -ForegroundColor Green
Start-ScheduledTask -TaskName $TaskName

$task = Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State
Write-Host "Task Status: $($task.State)" -ForegroundColor Cyan
Write-Host "Saegeul AI Companion is now active and ready for your mobile device!" -ForegroundColor Green
