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
    [string]$KeyPasswordFile,

    [switch]$PreflightOnly
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
$buildTools = Get-ChildItem (Join-Path $sdkRoot "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) {
    throw "Android SDK build-tools are not installed."
}
$apkSigner = Join-Path $buildTools.FullName "apksigner.bat"
if (-not (Test-Path -LiteralPath $apkSigner -PathType Leaf)) {
    throw "apksigner was not found at '$apkSigner'."
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

function Get-InstrumentationTargetPackage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    [xml]$document = (
        Invoke-Checked -Command $apkAnalyzer -Arguments @("manifest", "print", $ApkPath)
    ) -join "`n"
    $androidNamespace = "http://schemas.android.com/apk/res/android"
    $instrumentation = $document.SelectSingleNode("//instrumentation")
    if ($null -eq $instrumentation) {
        throw "Instrumentation declaration is missing from '$ApkPath'."
    }
    return $instrumentation.GetAttribute("targetPackage", $androidNamespace)
}

function Get-SigningCertificateSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    $output = @(& $apkSigner verify --print-certs $ApkPath 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner verification failed for '$ApkPath':`n$($output -join "`n")"
    }
    $digestLine = $output |
        Where-Object { $_ -match "^Signer #1 certificate SHA-256 digest:\s*(.+)$" } |
        Select-Object -First 1
    if ($null -eq $digestLine) {
        throw "Unable to read the signing certificate digest from '$ApkPath'."
    }
    [void]($digestLine -match "^Signer #1 certificate SHA-256 digest:\s*(.+)$")
    return ($Matches[1] -replace ":", "").Trim().ToLowerInvariant()
}

$mainApplicationId = Get-ApplicationId -ApkPath $resolvedMainApk
$hangulApplicationId = Get-ApplicationId -ApkPath $resolvedHangulApk
$testApplicationId = Get-ApplicationId -ApkPath $resolvedTestApk
$testTargetApplicationId = Get-InstrumentationTargetPackage -ApkPath $resolvedTestApk
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
if ($testTargetApplicationId -ne $mainApplicationId) {
    throw (
        "Instrumentation target '$testTargetApplicationId' does not match " +
        "release application '$mainApplicationId'."
    )
}
$mainSigningCertificate = Get-SigningCertificateSha256 -ApkPath $resolvedMainApk
$testSigningCertificate = Get-SigningCertificateSha256 -ApkPath $resolvedTestApk
if ($testSigningCertificate -ne $mainSigningCertificate) {
    throw (
        "Instrumentation certificate '$testSigningCertificate' does not match " +
        "release certificate '$mainSigningCertificate'."
    )
}

if ($PreflightOnly) {
    Write-Output "Release device verification preflight: PASS"
    Write-Output "Main application ID: $mainApplicationId"
    Write-Output "Hangul application ID: $hangulApplicationId"
    Write-Output "Instrumentation application ID: $testApplicationId"
    Write-Output "Instrumentation target: $testTargetApplicationId"
    Write-Output "Instrumentation signing certificate: $testSigningCertificate"
    return
}

$connectedDevices = @(& adb devices) |
    Where-Object { $_ -match "^(?<serial>[^\s]+)\s+device$" } |
    ForEach-Object { $Matches.serial }
if ($Serial -notin $connectedDevices) {
    throw "ADB device '$Serial' is not connected and authorized."
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
    Write-Output "Instrumentation target: $testTargetApplicationId"
    Write-Output "Instrumentation signing certificate: $testSigningCertificate"
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
