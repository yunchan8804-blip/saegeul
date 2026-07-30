# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-OptionalPropertyValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

$resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem

$archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApkPath)
try {
    $requiredEntries = @(
        "assets/legal/NOTICE.txt",
        "assets/legal/FORK-NOTICE.txt",
        "res/raw/aboutlibraries.json"
    )
    foreach ($entryName in $requiredEntries) {
        $entry = $archive.GetEntry($entryName)
        if ($null -eq $entry -or $entry.Length -eq 0) {
            throw "Required legal entry '$entryName' is missing or empty."
        }
    }

    $forbiddenEntryPattern = [regex]::new(
        "(?i)" +
        "fcitx5-chinese-addons|" +
        "pinyin\.lua|" +
        "assets/usr/share/opencc/|" +
        "assets/usr/share/fcitx5/(?:chttrans|pinyin|pinyinhelper|punctuation|table|inputmethod)/|" +
        "assets/usr/share/fcitx5/addon/(?:chttrans|fullwidth|pinyin|pinyinhelper|punctuation|table)\.conf"
    )
    $forbiddenEntries = @(
        $archive.Entries |
            Where-Object { $forbiddenEntryPattern.IsMatch($_.FullName) }
    )
    if ($forbiddenEntries.Count -ne 0) {
        $names = $forbiddenEntries | ForEach-Object { $_.FullName }
        throw "Excluded Chinese Addons content is still packaged: $($names -join ', ')."
    }

    $metadataEntry = $archive.GetEntry("res/raw/aboutlibraries.json")
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try {
        $metadata = $reader.ReadToEnd() | ConvertFrom-Json
    } finally {
        $reader.Dispose()
    }

    $libraries = @($metadata.libraries)
    $licenses = @(
        $metadata.licenses.PSObject.Properties |
            ForEach-Object { $_.Value }
    )
    if ($libraries.Count -eq 0) {
        throw "No dependency records were embedded in the APK."
    }
    if ($licenses.Count -eq 0) {
        throw "No license records were embedded in the APK."
    }

    $missingContent = @(
        $licenses |
            Where-Object { [string]::IsNullOrWhiteSpace($_.content) }
    )
    if ($missingContent.Count -ne 0) {
        $names = $missingContent | ForEach-Object {
            (Get-OptionalPropertyValue -InputObject $_ -Name "spdxId") ?? $_.name
        }
        throw "License text is missing for: $($names -join ', ')."
    }

    $unknownLicenses = @(
        $licenses |
            Where-Object {
                $_.name -match "(?i)unknown" -or
                (Get-OptionalPropertyValue -InputObject $_ -Name "spdxId") -match "(?i)unknown"
            }
    )
    if ($unknownLicenses.Count -ne 0) {
        throw "Unknown license records found: $($unknownLicenses.Count)."
    }

    $librariesWithoutLicenses = @(
        $libraries |
            Where-Object { @($_.licenses).Count -eq 0 }
    )
    if ($librariesWithoutLicenses.Count -ne 0) {
        $names = $librariesWithoutLicenses | ForEach-Object { $_.uniqueId }
        throw "Libraries without a declared license: $($names -join ', ')."
    }

    $forbiddenLibraries = @(
        $libraries |
            Where-Object {
                $_.uniqueId -match "(?i)fcitx5-chinese-addons|opencc"
            }
    )
    if ($forbiddenLibraries.Count -ne 0) {
        $names = $forbiddenLibraries | ForEach-Object { $_.uniqueId }
        throw "Excluded libraries are still declared in the APK: $($names -join ', ')."
    }

    $knownLicenseIds = @(
        $licenses |
            ForEach-Object {
                $_.hash
                Get-OptionalPropertyValue -InputObject $_ -Name "spdxId"
            } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique
    )
    $unresolvedLicenseIds = @(
        $libraries |
            ForEach-Object { $_.licenses } |
            Sort-Object -Unique |
            Where-Object { $_ -notin $knownLicenseIds }
    )
    if ($unresolvedLicenseIds.Count -ne 0) {
        throw "Libraries reference missing license records: $($unresolvedLicenseIds -join ', ')."
    }

    $requiredSpdxIds = @("Apache-2.0", "LGPL-2.1-or-later")
    foreach ($spdxId in $requiredSpdxIds) {
        $matches = @(
            $licenses |
                Where-Object {
                    (Get-OptionalPropertyValue -InputObject $_ -Name "spdxId") -eq $spdxId
                }
        )
        $shortTexts = @($matches | Where-Object { $_.content.Length -lt 1000 })
        if ($matches.Count -eq 0 -or $shortTexts.Count -ne 0) {
            throw "A full '$spdxId' license text is not embedded in the APK."
        }
    }

    $gpl2Licenses = @(
        $licenses |
            Where-Object {
                (Get-OptionalPropertyValue -InputObject $_ -Name "spdxId") -in @(
                    "GPL-2.0-only",
                    "GPL-2.0-or-later"
                )
            }
    )
    if ($gpl2Licenses.Count -eq 0 -or @($gpl2Licenses | Where-Object {
                $_.content.Length -lt 1000
            }).Count -ne 0) {
        throw "A full GPL 2.0 license text is not embedded in the APK."
    }

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApkPath).Hash.ToLowerInvariant()
    Write-Output "APK license audit: PASS"
    Write-Output "APK: $resolvedApkPath"
    Write-Output "SHA-256: $hash"
    Write-Output "Libraries: $($libraries.Count)"
    Write-Output "License records: $($licenses.Count)"
    Write-Output "Unknown licenses: 0"
    Write-Output "Missing full texts: 0"
    Write-Output "Excluded Chinese Addons entries: 0"
} finally {
    $archive.Dispose()
}
