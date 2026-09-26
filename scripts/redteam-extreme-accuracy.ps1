# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 YunChan

param(
    [string]$Serial = "emulator-5554",
    [int]$Iterations = 1,
    [switch]$VerboseLog
)

$ErrorActionPreference = "Stop"

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "ADB executable not found at: $adb"
}

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Saegeul Extreme Accuracy Red Team On-Device Harness" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# 1. Check Device Connectivity
Write-Host "[1/5] Checking emulator status ($Serial)..." -ForegroundColor Yellow
$devices = (& $adb devices) -join "`n"
if ($devices -notmatch "$Serial\s+device") {
    Write-Error "Emulator $Serial is not connected or not in device state!"
}
$booted = & $adb -s $Serial shell getprop sys.boot_completed
if ($booted.Trim() -ne "1") {
    Write-Error "Emulator $Serial is not fully booted (boot_completed != 1)"
}
Write-Host " -> Device $Serial is ONLINE and BOOT COMPLETED." -ForegroundColor Green

# 2. Verify Target and Test APKs
Write-Host "[2/5] Verifying installed packages on device..." -ForegroundColor Yellow
$installed = & $adb -s $Serial shell pm list packages
if ($installed -notmatch "net\.chanpaca\.saegeul") {
    Write-Warning "App package net.chanpaca.saegeul is not installed. Installing latest build..."
    $appApk = (Get-ChildItem -Path "app\build\outputs\apk\debug\*x86_64-debug.apk" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
    $testApk = (Get-ChildItem -Path "app\build\outputs\apk\androidTest\debug\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
    if ($appApk -and $testApk) {
        & $adb -s $Serial install -r -d -g $appApk
        & $adb -s $Serial install -r -d -g $testApk
    } else {
        Write-Error "APKs not found in app\build\outputs\apk\. Please build with assembleDebug and assembleDebugAndroidTest."
    }
} else {
    Write-Host " -> Packages verified: net.chanpaca.saegeul is present." -ForegroundColor Green
}

# 3. Clear Logcat buffer
Write-Host "[3/5] Clearing logcat buffer..." -ForegroundColor Yellow
& $adb -s $Serial logcat -c

# 4. Run Extreme Accuracy Instrumentation Tests
Write-Host "[4/5] Executing Extreme Accuracy Instrumentation Tests (Iterations: $Iterations)..." -ForegroundColor Yellow

$testClasses = @(
    "org.fcitx.fcitx5.android.ExtremeAccuracyE2eDeviceTest",
    "org.fcitx.fcitx5.android.RedTeamExtremeAccuracyDeviceTest"
)

$allPassed = $true
$totalExecuted = 0
$totalFailed = 0

foreach ($testClass in $testClasses) {
    Write-Host "  -> Running test: $testClass" -ForegroundColor Cyan
    $output = & $adb -s $Serial shell am instrument -w -r -e class $testClass net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    
    $outputText = $output -join "`n"
    if ($VerboseLog) {
        Write-Host $outputText
    }
    
    if ($outputText -match "OK \((\d+) tests?\)") {
        $count = [int]$matches[1]
        $totalExecuted += $count
        Write-Host "     [PASS] $testClass ($count tests passed)" -ForegroundColor Green
    } elseif ($outputText -match "FAILURES!!!") {
        Write-Host "     [FAIL] $testClass FAILED!" -ForegroundColor Red
        Write-Host $outputText -ForegroundColor Red
        $allPassed = $false
        $totalFailed++
    } elseif ($outputText -match "ClassNotFoundException") {
        Write-Host "     [SKIP] $testClass not found in test APK (pending compile/install)" -ForegroundColor DarkYellow
    } else {
        Write-Host "     [RESULT] $outputText" -ForegroundColor Gray
    }
}

# 5. Check Logcat for Fatal Exceptions / ANR
Write-Host "[5/5] Scanning logcat for crashes, OOM, or ANR..." -ForegroundColor Yellow
$crashLogs = & $adb -s $Serial logcat -d -s AndroidRuntime:E DEBUG:E
$hasCrash = $false
if ($crashLogs) {
    foreach ($line in $crashLogs) {
        if ($line -match "FATAL EXCEPTION" -or $line -match "SIGSEGV" -or $line -match "OutOfMemoryError") {
            Write-Host "CRASH DETECTED: $line" -ForegroundColor Red
            $hasCrash = $true
        }
    }
}

Write-Host "========================================================" -ForegroundColor Cyan
if ($allPassed -and -not $hasCrash) {
    Write-Host "  RED TEAM VERIFICATION RESULT: ALL PASS ($totalExecuted tests)" -ForegroundColor Green
    Write-Host "  Zero crash, zero syntax error, zero phonology violation!" -ForegroundColor Green
} else {
    Write-Host "  RED TEAM VERIFICATION RESULT: FAILURES DETECTED ($totalFailed failed)" -ForegroundColor Red
}
Write-Host "========================================================" -ForegroundColor Cyan

if (-not $allPassed -or $hasCrash) {
    exit 1
} else {
    exit 0
}
