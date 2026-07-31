# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MainApkPath,

    [Parameter(Mandatory = $true)]
    [string]$HangulApkPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*){2,}$")]
    [string]$ExpectedApplicationId,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedProductName,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$ExpectedRepositoryUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$ExpectedPrivacyPolicyUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$ExpectedSourceArchiveUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[0-9a-fA-F:]{64,95}$")]
    [string]$ExpectedSigningCertificateSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($ExpectedApplicationId -match "(?i)fcitx") {
    throw "The independent application ID must not contain the Fcitx product name."
}
if ([string]::IsNullOrWhiteSpace($ExpectedProductName) -or $ExpectedProductName -match "(?i)fcitx") {
    throw "The independent product name is missing or still contains Fcitx."
}

$resolvedMainApk = (Resolve-Path -LiteralPath $MainApkPath).Path
$resolvedHangulApk = (Resolve-Path -LiteralPath $HangulApkPath).Path
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

function Invoke-ApkAnalyzer {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = @(& $apkAnalyzer @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "apkanalyzer $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

function Get-ManifestValues {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    [xml]$document = Invoke-ApkAnalyzer -Arguments @("manifest", "print", $ApkPath)
    $androidNamespace = "http://schemas.android.com/apk/res/android"
    $manager = [Xml.XmlNamespaceManager]::new($document.NameTable)
    $manager.AddNamespace("android", $androidNamespace)
    return @{
        Permissions = @(
            $document.SelectNodes("//permission|//uses-permission", $manager) |
                ForEach-Object { $_.GetAttribute("name", $androidNamespace) }
        )
        Actions = @(
            $document.SelectNodes("//action", $manager) |
                ForEach-Object { $_.GetAttribute("name", $androidNamespace) }
        )
        Metadata = @(
            $document.SelectNodes("//meta-data", $manager) |
                ForEach-Object { $_.GetAttribute("name", $androidNamespace) }
        )
        Authorities = @(
            $document.SelectNodes("//provider", $manager) |
                ForEach-Object { $_.GetAttribute("authorities", $androidNamespace) }
        )
        Schemes = @(
            $document.SelectNodes("//data", $manager) |
                ForEach-Object { $_.GetAttribute("scheme", $androidNamespace) } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
        QueriedPackages = @(
            $document.SelectNodes("//queries/package", $manager) |
                ForEach-Object { $_.GetAttribute("name", $androidNamespace) }
        )
    }
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

function Test-ApkRuntimeLinks {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $foundRepository = $false
    $foundPrivacyPolicy = $false
    $foundSourceArchive = $false
    $forbiddenLinks = [ordered]@{
        "https://github.com/fcitx5-android/fcitx5-android" = $false
        "https://fcitx5-android.github.io" = $false
        "https://jenkins.fcitx-im.org/job/android/job/fcitx5-android" = $false
        "https://play.google.com/store/apps/details?id=org.fcitx.fcitx5.android" = $false
        "https://github.com/yunchan8804/saegeul" = $false
        "https://saegeul.twentyoz.kr" = $false
    }

    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        foreach ($entry in $archive.Entries) {
            $isRuntimeConfiguration =
                $entry.FullName -match "^(?:classes\d*\.dex|resources\.arsc|AndroidManifest\.xml)$" -or
                $entry.FullName.StartsWith("assets/", [StringComparison]::Ordinal) -or
                $entry.FullName.StartsWith("res/raw/", [StringComparison]::Ordinal)
            if (-not $isRuntimeConfiguration -or $entry.Length -eq 0 -or $entry.Length -gt 32MB) {
                continue
            }
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $content = [Text.Encoding]::Latin1.GetString($memory.ToArray())
            } finally {
                $memory.Dispose()
                $stream.Dispose()
            }
            $foundRepository = $foundRepository -or $content.Contains(
                $ExpectedRepositoryUrl,
                [StringComparison]::Ordinal
            )
            $foundPrivacyPolicy = $foundPrivacyPolicy -or $content.Contains(
                $ExpectedPrivacyPolicyUrl,
                [StringComparison]::Ordinal
            )
            $foundSourceArchive = $foundSourceArchive -or $content.Contains(
                $ExpectedSourceArchiveUrl,
                [StringComparison]::Ordinal
            )
            foreach ($url in @($forbiddenLinks.Keys)) {
                if ($content.Contains($url, [StringComparison]::Ordinal)) {
                    $forbiddenLinks[$url] = $true
                }
            }
        }
    } finally {
        $archive.Dispose()
    }

    if (-not $foundRepository) {
        throw "The owned source repository URL is not embedded in the main APK."
    }
    if (-not $foundPrivacyPolicy) {
        throw "The owned privacy-policy URL is not embedded in the main APK."
    }
    if (-not $foundSourceArchive) {
        throw "The same-tag source archive URL is not embedded in the main APK."
    }
    $foundForbiddenLinks = @(
        $forbiddenLinks.GetEnumerator() |
            Where-Object { $_.Value } |
            ForEach-Object { $_.Key }
    )
    if ($foundForbiddenLinks.Count -ne 0) {
        throw "Old official-app links remain in the APK: $($foundForbiddenLinks -join ', ')."
    }
}

$mainApplicationId = Invoke-ApkAnalyzer -Arguments @(
    "manifest", "application-id", $resolvedMainApk
)
$expectedHangulId = "$ExpectedApplicationId.plugin.hangul"
$hangulApplicationId = Invoke-ApkAnalyzer -Arguments @(
    "manifest", "application-id", $resolvedHangulApk
)
if ($mainApplicationId -ne $ExpectedApplicationId) {
    throw "Main application ID mismatch: expected '$ExpectedApplicationId', got '$mainApplicationId'."
}
if ($hangulApplicationId -ne $expectedHangulId) {
    throw "Hangul application ID mismatch: expected '$expectedHangulId', got '$hangulApplicationId'."
}

$expectedCertificate = ($ExpectedSigningCertificateSha256 -replace ":", "").ToLowerInvariant()
$mainCertificate = Get-SigningCertificateSha256 -ApkPath $resolvedMainApk
$hangulCertificate = Get-SigningCertificateSha256 -ApkPath $resolvedHangulApk
if ($mainCertificate -ne $expectedCertificate) {
    throw "Main APK signing certificate mismatch: expected '$expectedCertificate', got '$mainCertificate'."
}
if ($hangulCertificate -ne $expectedCertificate) {
    throw "Hangul APK signing certificate mismatch: expected '$expectedCertificate', got '$hangulCertificate'."
}

$releaseName = Invoke-ApkAnalyzer -Arguments @(
    "resources", "value",
    "--config", "default",
    "--type", "string",
    "--name", "app_name_release",
    $resolvedMainApk
)
if ($releaseName -ne $ExpectedProductName) {
    throw "Release product name mismatch: expected '$ExpectedProductName', got '$releaseName'."
}

$mainManifest = Get-ManifestValues -ApkPath $resolvedMainApk
$hangulManifest = Get-ManifestValues -ApkPath $resolvedHangulApk
$expectedPluginAction = "$ExpectedApplicationId.plugin.MANIFEST"
$expectedPluginMetadata = "$ExpectedApplicationId.plugin.METADATA"
$expectedIpcPermission = "$ExpectedApplicationId.permission.IPC"
$expectedOAuthScheme = "$ExpectedApplicationId.oauth"

if ($expectedIpcPermission -notin $mainManifest.Permissions) {
    throw "Main APK does not declare the expected IPC permission '$expectedIpcPermission'."
}
if ($expectedPluginAction -notin $mainManifest.Actions) {
    throw "Main APK does not query the expected plugin action '$expectedPluginAction'."
}
if ($expectedOAuthScheme -notin $mainManifest.Schemes) {
    throw "Main APK does not declare the expected OAuth scheme '$expectedOAuthScheme'."
}
if ($expectedPluginAction -notin $hangulManifest.Actions) {
    throw "Hangul APK does not declare the expected plugin action '$expectedPluginAction'."
}
if ($expectedPluginMetadata -notin $hangulManifest.Metadata) {
    throw "Hangul APK does not declare the expected plugin metadata '$expectedPluginMetadata'."
}
if ($ExpectedApplicationId -notin $hangulManifest.QueriedPackages) {
    throw "Hangul APK does not query the main package '$ExpectedApplicationId'."
}

$foreignAuthorities = @(
    $mainManifest.Authorities |
        Where-Object {
            -not [string]::IsNullOrWhiteSpace($_) -and
            -not $_.StartsWith("$ExpectedApplicationId.", [StringComparison]::Ordinal)
        }
)
if ($foreignAuthorities.Count -ne 0) {
    throw "Provider authorities outside the independent application ID found: $($foreignAuthorities -join ', ')."
}

$oldPublicPrefixes = @(
    "org.fcitx.fcitx5.android",
    "kr.twentyoz.saegeul"
)
$publicValues = @(
    $mainManifest.Permissions
    $mainManifest.Actions
    $mainManifest.Metadata
    $mainManifest.Authorities
    $mainManifest.Schemes
    $hangulManifest.Permissions
    $hangulManifest.Actions
    $hangulManifest.Metadata
    $hangulManifest.Authorities
    $hangulManifest.Schemes
    $hangulManifest.QueriedPackages
)
$oldPublicValues = @(
    $publicValues |
        Where-Object {
            if ([string]::IsNullOrWhiteSpace($_)) {
                return $false
            }
            $value = $_
            return $null -ne (
                $oldPublicPrefixes |
                    Where-Object { $value.StartsWith($_, [StringComparison]::Ordinal) } |
                    Select-Object -First 1
            )
        }
)
if ($oldPublicValues.Count -ne 0) {
    throw "Old public Fcitx Android identifiers remain: $($oldPublicValues -join ', ')."
}

Test-ApkRuntimeLinks -ApkPath $resolvedMainApk

Write-Output "Release identity audit: PASS"
Write-Output "Product: $ExpectedProductName"
Write-Output "Main application ID: $mainApplicationId"
Write-Output "Hangul application ID: $hangulApplicationId"
Write-Output "OAuth scheme: $expectedOAuthScheme"
Write-Output "Plugin action: $expectedPluginAction"
Write-Output "Signing certificate SHA-256: $mainCertificate"
Write-Output "Repository URL: $ExpectedRepositoryUrl"
Write-Output "Privacy policy URL: $ExpectedPrivacyPolicyUrl"
Write-Output "Source archive URL: $ExpectedSourceArchiveUrl"
