# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

# Direct Boot(재부팅 뒤 첫 잠금 해제 전) 잠금 화면에서 키보드가 뜨는지 에뮬레이터에서 확인한다.
# 에뮬레이터에 화면 잠금 비밀번호를 걸고 재부팅한 뒤, 비밀번호 화면에서 대상 패키지의
# FATAL EXCEPTION이 0건이고 입력기가 실제로 보이는지(mInputShown=true) 본다.
# 실기기는 거부한다(serial이 emulator- 로 시작하지 않으면 exit 2).
# 종료 코드: 0 통과, 1 검사 실패, 2 에뮬레이터가 아닌 serial.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [string]$AppApk,

    [string]$PluginApk,

    [string]$Package = "net.chanpaca.saegeul.debug",

    [int]$BootTimeoutSeconds = 240,

    [int]$KeyboardSettleSeconds = 25
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $Serial.StartsWith("emulator-")) {
    [Console]::Error.WriteLine("거부: '$Serial'은 에뮬레이터가 아니다. 이 스크립트는 화면 잠금을 바꾸고 재부팅하므로 emulator- 로 시작하는 serial만 받는다.")
    exit 2
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$imeComponent = "$Package/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
$lockPassword = "saegeul1234"

function Resolve-Adb {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $fromPath) { return $fromPath.Source }
    $roots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA "Android/Sdk")) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($root in $roots) {
        $candidate = Join-Path $root "platform-tools/adb.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        $candidate = Join-Path $root "platform-tools/adb"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "adb를 찾지 못했다. PATH에 두거나 ANDROID_SDK_ROOT를 설정해 달라."
}

function Resolve-DebugApk([string]$ExplicitPath, [string]$RelativeDir, [string]$Label) {
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }
    $dir = Join-Path $repoRoot $RelativeDir
    $found = @(Get-ChildItem -LiteralPath $dir -Filter "*-x86_64-debug.apk" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($found.Count -eq 0) {
        throw "$Label x86_64 debug APK를 $dir 에서 찾지 못했다. 먼저 빌드하거나 인자로 경로를 넘겨 달라."
    }
    return $found[0].FullName
}

$adb = Resolve-Adb
$appApkPath = Resolve-DebugApk $AppApk "app/build/outputs/apk/debug" "앱"
$pluginApkPath = Resolve-DebugApk $PluginApk "plugin/hangul/build/outputs/apk/debug" "한글 플러그인"

function Invoke-Adb {
    $AdbArgs = $args
    $output = & $adb -s $Serial @AdbArgs 2>&1
    $code = $LASTEXITCODE
    return [pscustomobject]@{ ExitCode = $code; Text = (($output | ForEach-Object { "$_" }) -join "`n") }
}

function Invoke-AdbChecked {
    $AdbArgs = $args
    $result = Invoke-Adb @AdbArgs
    if ($result.ExitCode -ne 0) {
        throw "adb $($AdbArgs -join ' ') 실패(exit $($result.ExitCode)): $($result.Text)"
    }
    return $result.Text
}

function Wait-BootCompleted {
    $deadline = [DateTime]::UtcNow.AddSeconds($BootTimeoutSeconds)
    & $adb -s $Serial wait-for-device 2>&1 | Out-Null
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = Invoke-Adb shell getprop sys.boot_completed
        if ($result.ExitCode -eq 0 -and $result.Text.Trim() -eq "1") { return }
        Start-Sleep -Seconds 2
    }
    throw "부팅 완료를 ${BootTimeoutSeconds}초 안에 확인하지 못했다."
}

$devices = (& $adb devices 2>&1 | ForEach-Object { "$_" }) -join "`n"
if ($devices -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\s*$") {
    throw "serial '$Serial'이 adb devices에 device 상태로 없다.`n$devices"
}

$failures = New-Object System.Collections.Generic.List[string]
$evidence = New-Object System.Collections.Generic.List[string]
$lockSet = $false
$stayOn = $false

try {
    Write-Host "== 설치: $appApkPath"
    Invoke-AdbChecked install -r -t $appApkPath | Out-Null
    Write-Host "== 설치: $pluginApkPath"
    Invoke-AdbChecked install -r -t $pluginApkPath | Out-Null

    Write-Host "== 입력기 활성화: $imeComponent"
    Invoke-AdbChecked shell ime enable $imeComponent | Out-Null
    Invoke-AdbChecked shell ime set $imeComponent | Out-Null

    Write-Host "== 앱 1회 실행"
    Invoke-AdbChecked shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 5
    Invoke-AdbChecked shell input keyevent KEYCODE_HOME | Out-Null

    Write-Host "== 화면 잠금 비밀번호 설정"
    Invoke-AdbChecked shell locksettings set-password $lockPassword | Out-Null
    $lockSet = $true

    Write-Host "== 재부팅"
    Invoke-AdbChecked reboot | Out-Null
    Start-Sleep -Seconds 15
    Wait-BootCompleted

    Write-Host "== 잠금 화면 깨우고 비밀번호 화면 띄우기"
    $size = Invoke-AdbChecked shell wm size
    if ($size -notmatch "Physical size:\s*(\d+)x(\d+)") { throw "화면 크기를 읽지 못했다: $size" }
    $width = [int]$Matches[1]
    $height = [int]$Matches[2]
    $centerX = [int]($width / 2)
    $stayOn = $true
    Invoke-AdbChecked shell svc power stayon true | Out-Null
    $swipeFromY = [int]($height * 0.85)
    $swipeToY = [int]($height * 0.30)
    # 부팅 직후에는 첫 스와이프가 키가드 준비 전에 지나가기도 해서, 입력기가 보일 때까지 몇 초마다 다시 시도한다.
    # 비밀번호 화면은 10초쯤 뒤 스스로 닫히며 입력기도 함께 내려가므로 보이는 순간을 놓치지 않게 자주 확인한다.
    $inputShownSeen = $false
    $lastImeDump = ""
    $pollDeadline = [DateTime]::UtcNow.AddSeconds($KeyboardSettleSeconds)
    $attempt = 0
    while ([DateTime]::UtcNow -lt $pollDeadline) {
        if ($attempt % 8 -eq 0) {
            Invoke-AdbChecked shell input keyevent KEYCODE_WAKEUP | Out-Null
            Start-Sleep -Milliseconds 1500
            Invoke-AdbChecked shell input swipe $centerX $swipeFromY $centerX $swipeToY 300 | Out-Null
        }
        $attempt++
        Start-Sleep -Milliseconds 700
        $lastImeDump = Invoke-AdbChecked shell dumpsys input_method
        if ($lastImeDump -match "mInputShown=true" -and
            $lastImeDump -match "mCurMethodId=$([regex]::Escape($imeComponent))") {
            $inputShownSeen = $true
            Start-Sleep -Seconds 2
            break
        }
    }

    Write-Host "== 검사"
    $logcat = Invoke-AdbChecked logcat -d -b all -v threadtime
    $logLines = $logcat -split "`n"

    if (-not ($logLines | Where-Object { $_ -match "FcitxApplication: isDirectBootMode=true" })) {
        $failures.Add("Direct Boot에서 앱 프로세스가 시작된 증거(FcitxApplication: isDirectBootMode=true)가 logcat에 없다. 잠금 상태로 재부팅되지 않았거나 입력기가 시작되지 않았다.")
    } else {
        $evidence.Add("Direct Boot 진입 확인: isDirectBootMode=true 로그 있음")
    }

    $fatalBlocks = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $logLines.Count; $i++) {
        if ($logLines[$i] -notmatch "FATAL EXCEPTION") { continue }
        $window = $logLines[$i..([Math]::Min($i + 3, $logLines.Count - 1))]
        if ($window | Where-Object { $_ -match "Process:\s*$([regex]::Escape($Package))(\s|,|$)" }) {
            $end = [Math]::Min($i + 30, $logLines.Count - 1)
            $fatalBlocks.Add(($logLines[$i..$end] -join "`n"))
        }
    }
    if ($fatalBlocks.Count -gt 0) {
        $failures.Add("대상 패키지 FATAL EXCEPTION $($fatalBlocks.Count)건")
        foreach ($block in $fatalBlocks) { $evidence.Add($block) }
    } else {
        $evidence.Add("대상 패키지 FATAL EXCEPTION 0건")
    }

    $shownLines = @($lastImeDump -split "`n" | Where-Object { $_ -match "mInputShown=" })
    if (-not $inputShownSeen) {
        $failures.Add("비밀번호 화면을 띄운 뒤 ${KeyboardSettleSeconds}초 안에 dumpsys input_method에서 대상 입력기의 mInputShown=true를 확인하지 못했다.")
        $evidence.AddRange([string[]]@($shownLines | ForEach-Object { $_.Trim() }))
        $currentIme = @($lastImeDump -split "`n" | Where-Object { $_ -match "mCurMethodId|mCurId|mBoundMethod|mCurIntent" })
        $evidence.AddRange([string[]]@($currentIme | ForEach-Object { $_.Trim() }))
        $power = Invoke-Adb shell dumpsys power
        $window = Invoke-Adb shell dumpsys window
        $evidence.AddRange([string[]]@(($power.Text + "`n" + $window.Text) -split "`n" |
            Where-Object { $_ -match "mWakefulness=|mCurrentFocus|isKeyguardShowing" } |
            ForEach-Object { $_.Trim() }))
    } else {
        $evidence.Add(($shownLines | ForEach-Object { $_.Trim() }) -join " | ")
    }
}
catch {
    $failures.Add("스크립트 실행 오류: $($_.Exception.Message)")
}
finally {
    if ($stayOn) {
        $null = Invoke-Adb shell svc power stayon false
    }
    if ($lockSet) {
        Write-Host "== 잠금 해제와 비밀번호 제거"
        $null = Invoke-Adb shell input keyevent KEYCODE_WAKEUP
        $null = Invoke-Adb shell input text $lockPassword
        $null = Invoke-Adb shell input keyevent KEYCODE_ENTER
        Start-Sleep -Seconds 3
        $cleared = Invoke-Adb shell locksettings clear --old $lockPassword
        if ($cleared.ExitCode -ne 0 -or $cleared.Text -match "(?i)error|exception|fail") {
            $failures.Add("locksettings clear --old 실패. 에뮬레이터에 비밀번호 '$lockPassword'가 남아 있을 수 있다: $($cleared.Text)")
        }
    }
}

foreach ($line in $evidence) { Write-Host $line }

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { [Console]::Error.WriteLine("실패: $failure") }
    exit 1
}

Write-Host "통과: Direct Boot 잠금 화면에서 FATAL 0건, mInputShown=true"
exit 0
