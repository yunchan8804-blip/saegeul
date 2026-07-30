# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ArchivePath,

    [Parameter(Mandatory = $true)]
    [string]$Ref
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Repository,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = @(& git -C $Repository @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git -C '$Repository' $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$resolvedArchivePath = (Resolve-Path -LiteralPath $ArchivePath).Path
$checksumPath = "$resolvedArchivePath.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Missing checksum sidecar '$checksumPath'."
}

$checksumLine = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
if ($checksumLine -notmatch "^([0-9a-fA-F]{64})\s{2}(.+)$") {
    throw "Invalid SHA-256 sidecar format in '$checksumPath'."
}
$expectedHash = $Matches[1].ToLowerInvariant()
$expectedFileName = $Matches[2]
if ($expectedFileName -ne [IO.Path]::GetFileName($resolvedArchivePath)) {
    throw "Checksum sidecar names '$expectedFileName', not the supplied archive."
}
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedArchivePath).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedHash) {
    throw "SHA-256 mismatch: expected $expectedHash, got $actualHash."
}

$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryDirectory = Join-Path $temporaryBase ("fork-source-verify-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null

try {
    & tar -xzf $resolvedArchivePath -C $temporaryDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to extract '$resolvedArchivePath'."
    }

    $roots = @(Get-ChildItem -LiteralPath $temporaryDirectory -Directory)
    if ($roots.Count -ne 1) {
        throw "Source archive must contain exactly one root directory, found $($roots.Count)."
    }
    $sourceRoot = $roots[0].FullName
    $manifestPath = Join-Path $sourceRoot "SOURCE-MANIFEST.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "SOURCE-MANIFEST.json is missing from the archive root."
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

    $shortTag = if ($Ref.StartsWith("refs/tags/")) {
        $Ref.Substring("refs/tags/".Length)
    } else {
        $Ref
    }
    if ($manifest.tag -ne $shortTag) {
        throw "Manifest tag '$($manifest.tag)' does not match '$shortTag'."
    }

    $tagRef = "refs/tags/$shortTag"
    $expectedCommit = (Invoke-Git -Repository $repositoryRoot -Arguments @(
        "rev-parse",
        "$tagRef^{commit}"
    ) | Select-Object -First 1).Trim()
    if ($manifest.commit -ne $expectedCommit) {
        throw "Manifest commit '$($manifest.commit)' does not match tag commit '$expectedCommit'."
    }

    foreach ($requiredPath in @(
        "LICENSE",
        "gradlew",
        "gradlew.bat",
        "build.gradle.kts",
        "settings.gradle.kts",
        "scripts/create-source-archive.ps1",
        "scripts/verify-source-archive.ps1"
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $requiredPath) -PathType Leaf)) {
            throw "Required source/build file '$requiredPath' is missing."
        }
    }

    $forbiddenPattern = @(
        "(^|/)\.tmp-",
        "(^|/)\.codex-remote-attachments(/|$)",
        "^(build|obj|test-results|captures|artifacts)(/|$)",
        "^[^/]+\.(apk|aab|idsig|log|obj)$",
        "(^|/)\.git(/|$)"
    ) -join "|"
    $forbiddenFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Force |
        ForEach-Object {
            [IO.Path]::GetRelativePath($sourceRoot, $_.FullName).Replace("\", "/")
        } |
        Where-Object { $_ -match $forbiddenPattern })
    if ($forbiddenFiles.Count -gt 0) {
        throw "Forbidden temporary/build paths found in archive:`n$($forbiddenFiles -join "`n")"
    }

    $expectedSubmodules = @{}
    $submoduleState = Invoke-Git -Repository $repositoryRoot -Arguments @(
        "submodule",
        "status",
        "--recursive"
    )
    foreach ($line in $submoduleState) {
        if ($line -notmatch "^[ +U-]([0-9a-f]{40})\s+(.+?)(?:\s+\(|$)") {
            throw "Cannot parse submodule status '$line'."
        }
        $expectedSubmodules[$Matches[2].Replace("\", "/")] = $Matches[1]
    }

    $manifestSubmodules = @($manifest.submodules)
    if ($manifestSubmodules.Count -ne $expectedSubmodules.Count) {
        throw "Manifest has $($manifestSubmodules.Count) submodules; expected $($expectedSubmodules.Count)."
    }
    foreach ($submodule in $manifestSubmodules) {
        $path = [string]$submodule.path
        if (-not $expectedSubmodules.ContainsKey($path)) {
            throw "Unexpected submodule '$path' in manifest."
        }
        if ($submodule.commit -ne $expectedSubmodules[$path]) {
            throw "Submodule '$path' has commit '$($submodule.commit)', expected '$($expectedSubmodules[$path])'."
        }
        $exportedFiles = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot $path) -Recurse -File)
        if ($exportedFiles.Count -eq 0) {
            throw "Submodule '$path' has no exported source files."
        }
    }

    [ordered]@{
        archive = $resolvedArchivePath
        sha256 = $actualHash
        tag = $shortTag
        commit = $expectedCommit
        submoduleCount = $manifestSubmodules.Count
        forbiddenPathCount = 0
        status = "PASS"
    } | ConvertTo-Json
} finally {
    $resolvedTemporaryDirectory = [IO.Path]::GetFullPath($temporaryDirectory)
    if (
        (Test-Path -LiteralPath $resolvedTemporaryDirectory) -and
        $resolvedTemporaryDirectory.StartsWith($temporaryBase, [StringComparison]::OrdinalIgnoreCase)
    ) {
        Remove-Item -LiteralPath $resolvedTemporaryDirectory -Recurse -Force
    }
}
