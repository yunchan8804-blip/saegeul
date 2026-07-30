# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
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

function Resolve-XmlResourcePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $path = Invoke-ApkAnalyzer -Arguments @(
        "resources",
        "value",
        "--config",
        "default",
        "--type",
        "xml",
        "--name",
        $Name,
        $resolvedApk
    )
    if ($path -notmatch "^res/[^/]+\.xml$") {
        throw "Unexpected path '$path' for XML resource '$Name'."
    }
    return $path
}

$permissions = Invoke-ApkAnalyzer -Arguments @("manifest", "permissions", $resolvedApk)
foreach ($requiredPermission in @(
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO"
)) {
    if (-not $permissions.Contains($requiredPermission, [StringComparison]::Ordinal)) {
        throw "Expected privacy-sensitive permission '$requiredPermission' is missing."
    }
}

$stringNames = Invoke-ApkAnalyzer -Arguments @(
    "resources",
    "names",
    "--type",
    "string",
    "--config",
    "default",
    $resolvedApk
)
$requiredDisclosureResources = @(
    "ai_transmission_disclosure",
    "voice_microphone_disclosure_title",
    "voice_microphone_disclosure_message",
    "voice_meeting_disclosure_title",
    "voice_meeting_disclosure_message",
    "gif_disclosure_noto",
    "gif_disclosure_klipy",
    "gif_disclosure_commons",
    "gif_disclosure_giphy",
    "data_privacy_notice"
)
$packagedStringNames = @($stringNames -split "\r?\n")
foreach ($resource in $requiredDisclosureResources) {
    if ($packagedStringNames -notcontains $resource) {
        throw "Required privacy disclosure string '$resource' is missing from the APK."
    }
}

$privacyNotice = Invoke-ApkAnalyzer -Arguments @(
    "files",
    "cat",
    "--file",
    "assets/legal/DATA-PRIVACY.txt",
    $resolvedApk
)
foreach ($requiredText in @(
    "Clipboard history is off by default",
    "store=false",
    "separate just-in-time disclosure and consent",
    "random app identifier",
    "approximate location",
    "in-app search history",
    "other user-generated content",
    "voice or sound recordings",
    "device or other ID",
    "app interactions"
)) {
    if (-not $privacyNotice.Contains($requiredText, [StringComparison]::Ordinal)) {
        throw "Embedded privacy notice is missing required statement '$requiredText'."
    }
}

foreach ($backupRuleName in @("full_backup_content", "data_extraction_rules")) {
    $backupRule = Resolve-XmlResourcePath -Name $backupRuleName
    $xml = Invoke-ApkAnalyzer -Arguments @(
        "resources",
        "xml",
        "--file",
        $backupRule,
        $resolvedApk
    )
    foreach ($clipboardDatabase in @("clbdb", "clbdb-wal", "clbdb-shm", "clbdb-journal")) {
        if (-not $xml.Contains($clipboardDatabase, [StringComparison]::Ordinal)) {
            throw "'$backupRule' does not exclude clipboard database '$clipboardDatabase'."
        }
    }
}

Write-Output "Release privacy verification: PASS"
Write-Output "APK: $resolvedApk"
Write-Output "Disclosure resources: $($requiredDisclosureResources.Count)"
Write-Output "Clipboard backup exclusions: PASS"
