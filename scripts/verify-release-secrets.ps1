# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [Alias("ApkPath")]
    [string]$PackagePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$secretPatterns = [ordered]@{
    "private key" = "-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"
    "OpenAI API key" = "(?<![A-Za-z0-9])sk-(?:proj-)?[A-Za-z0-9_-]{20,}"
    "Google API key" = "(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{35}"
    "GitHub token" = "(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{30,}"
    "GitLab token" = "(?<![A-Za-z0-9])glpat-[A-Za-z0-9_-]{20,}"
    "Slack token" = "(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{20,}"
    "AWS access key" = "(?<![A-Za-z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}"
    "OAuth client secret" = "(?i)(?:client[_-]?secret|oauth[_-]?secret)\s*[:=]\s*[`"'](?!\s*(?:example|placeholder|test|unset|none)?\s*[`"'])[^`"'\r\n]{8,}[`"']"
}
$combinedPattern = (
    $secretPatterns.Values |
        ForEach-Object { "(?:$_)" }
) -join "|"
$secretRegex = [regex]::new(
    $combinedPattern,
    [Text.RegularExpressions.RegexOptions]::Compiled -bor
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
)

function Test-ContentForSecrets {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Source,

        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    if ($secretRegex.IsMatch($Content)) {
        throw "Potential secret detected in '$Source'."
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedPackagePath = (Resolve-Path -LiteralPath $PackagePath).Path

$trackedFiles = @(& git -C $repositoryRoot ls-files)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to enumerate tracked source files."
}
$matches = @(
    & git -C $repositoryRoot grep -n -I -P -e $combinedPattern -- . `
        ":(exclude)scripts/verify-release-secrets.ps1" 2>&1
)
if ($LASTEXITCODE -eq 0) {
    throw "Potential secret detected in tracked source:`n$($matches -join "`n")"
}
if ($LASTEXITCODE -ne 1) {
    throw "git grep failed during the tracked-source secret audit:`n$($matches -join "`n")"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedPackagePath)
try {
    foreach ($entry in $archive.Entries) {
        $isRuntimeConfiguration =
            $entry.FullName -match "^(?:classes\d*\.dex|resources\.arsc|AndroidManifest\.xml)$" -or
            $entry.FullName -match "^base/(?:dex/classes\d*\.dex|manifest/AndroidManifest\.xml|resources\.pb)$" -or
            $entry.FullName.StartsWith("assets/", [StringComparison]::Ordinal) -or
            $entry.FullName.StartsWith("res/", [StringComparison]::Ordinal) -or
            $entry.FullName.StartsWith("base/assets/", [StringComparison]::Ordinal) -or
            $entry.FullName.StartsWith("base/res/", [StringComparison]::Ordinal)
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
        Test-ContentForSecrets -Source "PACKAGE:$($entry.FullName)" -Content $content
    }
} finally {
    $archive.Dispose()
}

Write-Output "Release secret audit: PASS"
Write-Output "Tracked files scanned: $($trackedFiles.Count)"
Write-Output "Package: $resolvedPackagePath"
