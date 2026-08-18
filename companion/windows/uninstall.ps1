# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

param(
    [string]$TaskName = "Saegeul AI Companion",
    [string]$InstallDir = "$env:LOCALAPPDATA\Saegeul\companion"
)

Write-Host "Stopping and removing $TaskName..." -ForegroundColor Yellow
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue

if (Test-Path $InstallDir) {
    Write-Host "Cleaning up files in $InstallDir..." -ForegroundColor Yellow
    Remove-Item -Path $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "[SUCCESS] Saegeul AI Companion uninstalled successfully." -ForegroundColor Green
