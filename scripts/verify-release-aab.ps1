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
    $requiredHangulAssets = @(
        "base/assets/usr/share/fcitx5/addon/hangul.conf",
        "base/assets/usr/share/fcitx5/inputmethod/hangul.conf",
        "base/assets/usr/share/fcitx5/hangul/completion.txt",
        "base/assets/usr/share/libhangul/hanja/hanja.txt"
    )
    foreach ($entryName in @(
        "base/assets/descriptor.json",
        "base/assets/legal/NOTICE.txt",
        "base/assets/legal/FORK-NOTICE.txt",
        "base/assets/legal/DATA-PRIVACY.txt",
        "base/res/raw/aboutlibraries.json"
    ) + $requiredHangulAssets) {
        $entry = $archive.GetEntry($entryName)
        if ($null -eq $entry -or $entry.Length -eq 0) {
            throw "Required AAB entry '$entryName' is missing or empty."
        }
    }

    $descriptorEntry = $archive.GetEntry("base/assets/descriptor.json")
    $descriptorReader = [IO.StreamReader]::new($descriptorEntry.Open())
    try {
        $descriptor = $descriptorReader.ReadToEnd() | ConvertFrom-Json
    } finally {
        $descriptorReader.Dispose()
    }
    $descriptorPaths = @($descriptor.files.PSObject.Properties.Name)
    foreach ($entryName in $requiredHangulAssets) {
        $assetPath = $entryName.Substring("base/assets/".Length)
        if ($assetPath -notin $descriptorPaths) {
            throw "Bundled Hangul asset '$assetPath' is missing from descriptor.json."
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
    foreach ($abi in $packagedAbis) {
        $hangulLibrary = $archive.GetEntry("base/lib/$abi/libhangul.so")
        if ($null -eq $hangulLibrary -or $hangulLibrary.Length -eq 0) {
            throw "The AAB is missing the bundled Hangul engine for ABI '$abi'."
        }
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
        "base/assets/usr/share/fcitx5/(?:chttrans|pinyin|pinyinhelper|punctuation|table)/|" +
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

    $unexpectedInputMethods = @(
        $archive.Entries |
            Where-Object {
                $_.FullName.StartsWith(
                    "base/assets/usr/share/fcitx5/inputmethod/",
                    [StringComparison]::Ordinal
                ) -and
                -not $_.FullName.EndsWith("/") -and
                $_.FullName -ne "base/assets/usr/share/fcitx5/inputmethod/hangul.conf"
            } |
            ForEach-Object { $_.FullName }
    )
    if ($unexpectedInputMethods.Count -ne 0) {
        throw "Unexpected input methods are packaged: $($unexpectedInputMethods -join ', ')."
    }

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedAabPath).Hash.ToLowerInvariant()
    Write-Output "AAB structure audit: PASS"
    Write-Output "AAB: $resolvedAabPath"
    Write-Output "SHA-256: $hash"
    Write-Output "Native ABIs: $($packagedAbis -join ', ')"
    Write-Output "Bundled Hangul assets, descriptor, and native engine: PASS"
    Write-Output "Legal and privacy assets: PASS"
    Write-Output "Excluded Chinese Addons entries: 0"
    Write-Output "Unexpected input methods: 0"
} finally {
    $archive.Dispose()
}
