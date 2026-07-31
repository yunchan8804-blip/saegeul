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

    [string]$KeystorePasswordFile,

    [string]$KeyPasswordFile,

    [string]$KeystorePasswordCliXml,

    [string]$KeyPasswordCliXml,

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

$sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
} elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $env:ANDROID_HOME
} else {
    throw "ANDROID_SDK_ROOT or ANDROID_HOME is required."
}
$apkAnalyzerName = if ($IsWindows) { "apkanalyzer.bat" } else { "apkanalyzer" }
$apkAnalyzer = Join-Path $sdkRoot "cmdline-tools/latest/bin/$apkAnalyzerName"
if (-not (Test-Path -LiteralPath $apkAnalyzer -PathType Leaf)) {
    throw "apkanalyzer was not found at '$apkAnalyzer'."
}
$buildTools = Get-ChildItem (Join-Path $sdkRoot "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) {
    throw "Android SDK build-tools are not installed."
}
$apkSignerName = if ($IsWindows) { "apksigner.bat" } else { "apksigner" }
$apkSigner = Join-Path $buildTools.FullName $apkSignerName
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

function Get-ApkClassInventory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    $output = Invoke-Checked -Command $apkAnalyzer -Arguments @(
        "dex",
        "packages",
        $ApkPath
    )
    $definitions = [Collections.Generic.HashSet[string]]::new()
    $references = [Collections.Generic.HashSet[string]]::new()
    foreach ($line in $output) {
        if ($line -notmatch "^C\s+(?<kind>[dr])\s+\d+\s+\d+\s+\d+\s+(?<name>.+)$") {
            continue
        }
        $className = ($Matches.name.Trim() -replace "(\[\])+$", "")
        if ($Matches.kind -eq "d") {
            [void]$definitions.Add($className)
        } else {
            [void]$references.Add($className)
        }
    }
    return [pscustomobject]@{
        Definitions = $definitions
        References = $references
    }
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
$mainClassInventory = Get-ApkClassInventory -ApkPath $resolvedMainApk
$testClassInventory = Get-ApkClassInventory -ApkPath $resolvedTestApk
$platformClassPattern = (
    "^(android|java|javax|dalvik|sun|org\.w3c|org\.xml)\." +
    "|^(boolean|byte|char|double|float|int|long|short|void)$"
)
$unresolvedRuntimeClasses = @(
    $testClassInventory.References |
        Where-Object {
            -not $testClassInventory.Definitions.Contains($_) -and
            -not $mainClassInventory.Definitions.Contains($_) -and
            $_ -notmatch $platformClassPattern
        } |
        Sort-Object
)
if ($unresolvedRuntimeClasses.Count -gt 0) {
    throw (
        "Instrumentation runtime references classes that are not defined by either the main " +
        "APK or AndroidTest APK:`n$($unresolvedRuntimeClasses -join "`n")"
    )
}

if ($PreflightOnly) {
    Write-Output "Release device verification preflight: PASS"
    Write-Output "Main application ID: $mainApplicationId"
    Write-Output "Hangul application ID: $hangulApplicationId"
    Write-Output "Instrumentation application ID: $testApplicationId"
    Write-Output "Instrumentation target: $testTargetApplicationId"
    Write-Output "Instrumentation signing certificate: $testSigningCertificate"
    Write-Output "Instrumentation runtime closure: PASS"
    return
}

$hasPasswordFiles = -not [string]::IsNullOrWhiteSpace($KeystorePasswordFile) -and
    -not [string]::IsNullOrWhiteSpace($KeyPasswordFile)
$hasEncryptedPasswords = -not [string]::IsNullOrWhiteSpace($KeystorePasswordCliXml) -and
    -not [string]::IsNullOrWhiteSpace($KeyPasswordCliXml)
if ($hasPasswordFiles -eq $hasEncryptedPasswords) {
    throw (
        "Provide exactly one complete password source: " +
        "-KeystorePasswordFile/-KeyPasswordFile or " +
        "-KeystorePasswordCliXml/-KeyPasswordCliXml."
    )
}

$resolvedKeystorePasswordFile = $null
$resolvedKeyPasswordFile = $null
$resolvedKeystorePasswordCliXml = $null
$resolvedKeyPasswordCliXml = $null
if ($hasPasswordFiles) {
    $resolvedKeystorePasswordFile = (Resolve-Path -LiteralPath $KeystorePasswordFile).Path
    $resolvedKeyPasswordFile = (Resolve-Path -LiteralPath $KeyPasswordFile).Path
} else {
    $resolvedKeystorePasswordCliXml = (
        Resolve-Path -LiteralPath $KeystorePasswordCliXml
    ).Path
    $resolvedKeyPasswordCliXml = (Resolve-Path -LiteralPath $KeyPasswordCliXml).Path
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
    if ($hasEncryptedPasswords) {
        $resolvedKeystorePasswordFile = Join-Path $temporaryDirectory "keystore-password.txt"
        $resolvedKeyPasswordFile = Join-Path $temporaryDirectory "key-password.txt"
        $passwordInputs = @(
            @($resolvedKeystorePasswordCliXml, $resolvedKeystorePasswordFile),
            @($resolvedKeyPasswordCliXml, $resolvedKeyPasswordFile)
        )
        foreach ($passwordInput in $passwordInputs) {
            $securePassword = Import-Clixml -LiteralPath $passwordInput[0]
            if ($securePassword -isnot [Security.SecureString]) {
                throw "Encrypted password input '$($passwordInput[0])' is not a SecureString."
            }
            $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(
                $securePassword
            )
            try {
                $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
                    $passwordPointer
                )
                [IO.File]::WriteAllText(
                    $passwordInput[1],
                    $plainPassword,
                    [Text.UTF8Encoding]::new($false)
                )
            } finally {
                $plainPassword = $null
                if ($passwordPointer -ne [IntPtr]::Zero) {
                    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
                }
            }
        }
    }
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
