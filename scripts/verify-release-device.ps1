# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$MainApkPath,

    [Parameter(Mandatory = $true)]
    [string]$MainAabPath,

    [Parameter(Mandatory = $true)]
    [string]$HangulApkPath,

    [Parameter(Mandatory = $true)]
    [string]$AndroidTestApkPath,

    [Parameter(Mandatory = $true)]
    [string]$BundletoolJarPath,

    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [Parameter(Mandatory = $true)]
    [string]$KeyAlias,

    [Parameter(Mandatory = $true)]
    [string]$KeystorePasswordFile,

    [Parameter(Mandatory = $true)]
    [string]$KeyPasswordFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedMainApk = (Resolve-Path -LiteralPath $MainApkPath).Path
$resolvedMainAab = (Resolve-Path -LiteralPath $MainAabPath).Path
$resolvedHangulApk = (Resolve-Path -LiteralPath $HangulApkPath).Path
$resolvedTestApk = (Resolve-Path -LiteralPath $AndroidTestApkPath).Path
$resolvedBundletool = (Resolve-Path -LiteralPath $BundletoolJarPath).Path
$resolvedKeystore = (Resolve-Path -LiteralPath $KeystorePath).Path
$resolvedKeystorePasswordFile = (Resolve-Path -LiteralPath $KeystorePasswordFile).Path
$resolvedKeyPasswordFile = (Resolve-Path -LiteralPath $KeyPasswordFile).Path

$sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
} elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $env:ANDROID_HOME
} else {
    throw "ANDROID_SDK_ROOT or ANDROID_HOME is required."
}
$apkAnalyzer = Join-Path $sdkRoot "cmdline-tools/latest/bin/apkanalyzer.bat"
if (-not (Test-Path -LiteralPath $apkAnalyzer -PathType Leaf)) {
    throw "apkanalyzer was not found at '$apkAnalyzer'."
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = @(& $Command @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "$Command $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Get-ApplicationId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    $output = Invoke-Checked -Command $apkAnalyzer -Arguments @(
            "manifest",
            "application-id",
            $ApkPath
        )
    return (($output -join "`n").Trim())
}

$connectedDevices = @(& adb devices) |
    Where-Object { $_ -match "^(?<serial>[^\s]+)\s+device$" } |
    ForEach-Object { $Matches.serial }
if ($Serial -notin $connectedDevices) {
    throw "ADB device '$Serial' is not connected and authorized."
}

$mainApplicationId = Get-ApplicationId -ApkPath $resolvedMainApk
$hangulApplicationId = Get-ApplicationId -ApkPath $resolvedHangulApk
$testApplicationId = Get-ApplicationId -ApkPath $resolvedTestApk
$validHangulApplicationIds = @("$mainApplicationId.plugin.hangul")
if ($mainApplicationId.EndsWith(".debug", [StringComparison]::Ordinal)) {
    $releaseApplicationId = $mainApplicationId.Substring(
        0,
        $mainApplicationId.Length - ".debug".Length
    )
    $validHangulApplicationIds += "$releaseApplicationId.plugin.hangul.debug"
}
if ($hangulApplicationId -notin $validHangulApplicationIds) {
    throw (
        "Hangul application ID '$hangulApplicationId' does not match " +
        "one of [$($validHangulApplicationIds -join ', ')]."
    )
}
if ($testApplicationId -ne "$mainApplicationId.test") {
    throw "Test application ID '$testApplicationId' does not match '$mainApplicationId.test'."
}

$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) (
    "fcitx5-android-device-gate-" + [Guid]::NewGuid().ToString("N")
)
[void](New-Item -ItemType Directory -Path $temporaryDirectory)
try {
    $apksPath = Join-Path $temporaryDirectory "main.apks"
    Invoke-Checked -Command "java" -Arguments @(
        "-jar",
        $resolvedBundletool,
        "build-apks",
        "--bundle=$resolvedMainAab",
        "--output=$apksPath",
        "--mode=universal",
        "--ks=$resolvedKeystore",
        "--ks-key-alias=$KeyAlias",
        "--ks-pass=file:$resolvedKeystorePasswordFile",
        "--key-pass=file:$resolvedKeyPasswordFile",
        "--overwrite"
    ) | Write-Output
    Invoke-Checked -Command "java" -Arguments @(
        "-jar",
        $resolvedBundletool,
        "install-apks",
        "--apks=$apksPath",
        "--device-id=$Serial"
    ) | Write-Output

    Invoke-Checked -Command "adb" -Arguments @(
        "-s", $Serial, "install", "-r", "-t", $resolvedMainApk
    ) | Write-Output
    Invoke-Checked -Command "adb" -Arguments @(
        "-s", $Serial, "install", "-r", "-t", $resolvedHangulApk
    ) | Write-Output
    Invoke-Checked -Command "adb" -Arguments @(
        "-s", $Serial, "install", "-r", "-t", $resolvedTestApk
    ) | Write-Output

    $pluginAction = "$mainApplicationId.plugin.MANIFEST"
    $pluginActivities = Invoke-Checked -Command "adb" -Arguments @(
        "-s",
        $Serial,
        "shell",
        "cmd",
        "package",
        "query-activities",
        "--brief",
        "-a",
        $pluginAction
    )
    if (-not (($pluginActivities -join "`n").Contains(
                "$hangulApplicationId/",
                [StringComparison]::Ordinal
            ))) {
        throw "Android package manager did not resolve Hangul plugin action '$pluginAction'."
    }

    $instrumentationOutput = Invoke-Checked -Command "adb" -Arguments @(
        "-s",
        $Serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "$testApplicationId/androidx.test.runner.AndroidJUnitRunner"
    )
    $instrumentationText = $instrumentationOutput -join "`n"
    if ($instrumentationText -notmatch "(?m)^OK \(\d+ tests?\)$" -or
        $instrumentationText.Contains("FAILURES!!!", [StringComparison]::Ordinal)
    ) {
        throw "Instrumentation gate did not report a clean pass:`n$instrumentationText"
    }

    Write-Output "Release device verification: PASS"
    Write-Output "Device: $Serial"
    Write-Output "Main application ID: $mainApplicationId"
    Write-Output "Hangul plugin discovery: PASS"
    Write-Output "Two-set Hangul composition: PASS"
    Write-Output "Legacy data migration: PASS"
} finally {
    if ((Resolve-Path -LiteralPath $temporaryDirectory).Path.StartsWith(
            [IO.Path]::GetTempPath(),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
