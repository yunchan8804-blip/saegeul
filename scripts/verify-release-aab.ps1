# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AabPath,

    [string[]]$ExpectedAbis = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedAabPath = (Resolve-Path -LiteralPath $AabPath).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem

$archive = [IO.Compression.ZipFile]::OpenRead($resolvedAabPath)
try {
    foreach ($entryName in @(
        "base/assets/legal/NOTICE.txt",
        "base/assets/legal/FORK-NOTICE.txt",
        "base/assets/legal/DATA-PRIVACY.txt",
        "base/res/raw/aboutlibraries.json"
    )) {
        $entry = $archive.GetEntry($entryName)
        if ($null -eq $entry -or $entry.Length -eq 0) {
            throw "Required AAB entry '$entryName' is missing or empty."
        }
    }

    $packagedAbis = @(
        $archive.Entries |
            ForEach-Object {
                if ($_.FullName -match "^base/lib/([^/]+)/[^/]+$") {
                    $Matches[1]
                }
            } |
            Sort-Object -Unique
    )
    if ($packagedAbis.Count -eq 0) {
        throw "The AAB does not contain native libraries."
    }

    $normalizedExpectedAbis = @(
        $ExpectedAbis |
            ForEach-Object { $_.Split(",") } |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique
    )
    if ($normalizedExpectedAbis.Count -ne 0) {
        $missingAbis = @($normalizedExpectedAbis | Where-Object { $_ -notin $packagedAbis })
        $unexpectedAbis = @($packagedAbis | Where-Object { $_ -notin $normalizedExpectedAbis })
        if ($missingAbis.Count -ne 0 -or $unexpectedAbis.Count -ne 0) {
            throw (
                "AAB ABI mismatch. Expected: [$($normalizedExpectedAbis -join ', ')]; " +
                "packaged: [$($packagedAbis -join ', ')]."
            )
        }
    }

    $forbiddenEntryPattern = [regex]::new(
        "(?i)" +
        "fcitx5-chinese-addons|" +
        "pinyin\.lua|" +
        "base/assets/usr/share/opencc/|" +
        "base/assets/usr/share/fcitx5/(?:chttrans|pinyin|pinyinhelper|punctuation|table|inputmethod)/|" +
        "base/assets/usr/share/fcitx5/addon/(?:chttrans|fullwidth|pinyin|pinyinhelper|punctuation|table)\.conf"
    )
    $forbiddenEntries = @(
        $archive.Entries |
            Where-Object { $forbiddenEntryPattern.IsMatch($_.FullName) } |
            ForEach-Object { $_.FullName }
    )
    if ($forbiddenEntries.Count -ne 0) {
        throw "Excluded Chinese Addons content is still packaged: $($forbiddenEntries -join ', ')."
    }

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedAabPath).Hash.ToLowerInvariant()
    Write-Output "AAB structure audit: PASS"
    Write-Output "AAB: $resolvedAabPath"
    Write-Output "SHA-256: $hash"
    Write-Output "Native ABIs: $($packagedAbis -join ', ')"
    Write-Output "Legal and privacy assets: PASS"
    Write-Output "Excluded Chinese Addons entries: 0"
} finally {
    $archive.Dispose()
}
