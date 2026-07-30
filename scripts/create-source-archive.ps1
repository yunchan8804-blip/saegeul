# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Ref,

    [string]$OutputDirectory = "artifacts/source",

    [switch]$Force
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

function Invoke-Tar {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & tar @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "tar $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$tagRef = if ($Ref.StartsWith("refs/tags/")) { $Ref } else { "refs/tags/$Ref" }

& git -C $repositoryRoot show-ref --verify --quiet $tagRef
if ($LASTEXITCODE -ne 0) {
    throw "'$Ref' is not a local tag. Fetch or create the exact annotated tag first."
}

$tagType = (Invoke-Git -Repository $repositoryRoot -Arguments @(
    "cat-file",
    "-t",
    $tagRef
) | Select-Object -First 1).Trim()
if ($tagType -ne "tag") {
    throw "'$Ref' must be an annotated tag, not a lightweight tag."
}

$commit = (Invoke-Git -Repository $repositoryRoot -Arguments @(
    "rev-parse",
    "$tagRef^{commit}"
) | Select-Object -First 1).Trim()
$head = (Invoke-Git -Repository $repositoryRoot -Arguments @(
    "rev-parse",
    "HEAD"
) | Select-Object -First 1).Trim()
if ($head -ne $commit) {
    throw "HEAD $head does not match tagged commit $commit. Check out the release tag first."
}

$trackedChanges = @(Invoke-Git -Repository $repositoryRoot -Arguments @(
    "status",
    "--porcelain",
    "--untracked-files=no"
))
if ($trackedChanges.Count -gt 0) {
    throw "Tracked files are dirty. Commit or restore them before creating a source archive."
}

$submoduleState = @(Invoke-Git -Repository $repositoryRoot -Arguments @(
    "submodule",
    "status",
    "--recursive"
))
$dirtySubmodules = @($submoduleState | Where-Object { $_ -match "^[+U-]" })
if ($dirtySubmodules.Count -gt 0) {
    throw "Submodules are missing or do not match the tagged gitlinks:`n$($dirtySubmodules -join "`n")"
}

$resolvedOutputDirectory = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputDirectory))
}
[IO.Directory]::CreateDirectory($resolvedOutputDirectory) | Out-Null

$shortTag = $tagRef.Substring("refs/tags/".Length)
$archiveStem = ($shortTag -replace "[^A-Za-z0-9._-]", "-") + "-source"
$archivePath = Join-Path $resolvedOutputDirectory "$archiveStem.tar.gz"
$checksumPath = "$archivePath.sha256"

foreach ($existingPath in @($archivePath, $checksumPath)) {
    if (Test-Path -LiteralPath $existingPath) {
        if (-not $Force) {
            throw "'$existingPath' already exists. Pass -Force to replace this generated artifact."
        }
        Remove-Item -LiteralPath $existingPath -Force
    }
}

$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryDirectory = Join-Path $temporaryBase ("fork-source-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
$submoduleRecords = [Collections.Generic.List[object]]::new()
$archiveIndex = 0

function Export-Submodules {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Repository,

        [Parameter(Mandatory = $true)]
        [string]$Treeish,

        [Parameter(Mandatory = $true)]
        [string]$ArchiveRelativeBase
    )

    $gitmodulesPath = Join-Path $Repository ".gitmodules"
    if (-not (Test-Path -LiteralPath $gitmodulesPath -PathType Leaf)) {
        return
    }

    $pathEntries = @(& git -C $Repository config -f .gitmodules --get-regexp `
        "^submodule\..*\.path$" 2>&1)
    if ($LASTEXITCODE -eq 1) {
        return
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read submodule paths from '$gitmodulesPath'."
    }

    foreach ($entry in $pathEntries) {
        if ($entry -notmatch "^(submodule\.(.+)\.path)\s+(.+)$") {
            throw "Cannot parse submodule config entry '$entry'."
        }

        $submoduleName = $Matches[2]
        $submodulePath = $Matches[3]
        $treeEntry = (Invoke-Git -Repository $Repository -Arguments @(
            "ls-tree",
            $Treeish,
            "--",
            $submodulePath
        ) | Select-Object -First 1)
        if ($treeEntry -notmatch "^160000 commit ([0-9a-f]{40})\t") {
            throw "'$submodulePath' is not a gitlink in tree '$Treeish'."
        }
        $submoduleCommit = $Matches[1]

        $submoduleRepository = [IO.Path]::GetFullPath((Join-Path $Repository $submodulePath))
        if (-not (Test-Path -LiteralPath $submoduleRepository -PathType Container)) {
            throw "Submodule '$submodulePath' is not initialized at '$submoduleRepository'."
        }

        $checkedOutCommit = (Invoke-Git -Repository $submoduleRepository -Arguments @(
            "rev-parse",
            "HEAD"
        ) | Select-Object -First 1).Trim()
        if ($checkedOutCommit -ne $submoduleCommit) {
            throw "Submodule '$submodulePath' is at $checkedOutCommit, expected $submoduleCommit."
        }

        & git -C $submoduleRepository cat-file -e "$submoduleCommit^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "Submodule '$submodulePath' does not contain commit $submoduleCommit."
        }

        $archiveRelativePath = if ([string]::IsNullOrEmpty($ArchiveRelativeBase)) {
            $submodulePath
        } else {
            "$ArchiveRelativeBase/$submodulePath"
        }
        $archiveRelativePath = $archiveRelativePath.Replace("\", "/")

        $url = @(& git -C $Repository config -f .gitmodules --get "submodule.$submoduleName.url")
        if ($LASTEXITCODE -ne 0 -or $url.Count -eq 0) {
            throw "Submodule '$submoduleName' has no URL in '$gitmodulesPath'."
        }

        $script:archiveIndex += 1
        $submoduleTar = Join-Path $temporaryDirectory "submodule-$script:archiveIndex.tar"
        $prefix = "$archiveStem/$archiveRelativePath/"
        Invoke-Git -Repository $submoduleRepository -Arguments @(
            "archive",
            "--format=tar",
            "--prefix=$prefix",
            "--output=$submoduleTar",
            $submoduleCommit
        ) | Out-Null
        Invoke-Tar -Arguments @("-xf", $submoduleTar, "-C", $temporaryDirectory)
        Remove-Item -LiteralPath $submoduleTar -Force

        $submoduleRecords.Add([ordered]@{
            path = $archiveRelativePath
            commit = $submoduleCommit
            url = $url[0].Trim()
        })

        Export-Submodules `
            -Repository $submoduleRepository `
            -Treeish $submoduleCommit `
            -ArchiveRelativeBase $archiveRelativePath
    }
}

try {
    $rootTar = Join-Path $temporaryDirectory "root.tar"
    Invoke-Git -Repository $repositoryRoot -Arguments @(
        "archive",
        "--format=tar",
        "--prefix=$archiveStem/",
        "--output=$rootTar",
        $commit
    ) | Out-Null
    Invoke-Tar -Arguments @("-xf", $rootTar, "-C", $temporaryDirectory)
    Remove-Item -LiteralPath $rootTar -Force

    Export-Submodules `
        -Repository $repositoryRoot `
        -Treeish $commit `
        -ArchiveRelativeBase ""

    $rootTree = (Invoke-Git -Repository $repositoryRoot -Arguments @(
        "rev-parse",
        "$commit^{tree}"
    ) | Select-Object -First 1).Trim()
    $commitTimestamp = (Invoke-Git -Repository $repositoryRoot -Arguments @(
        "show",
        "-s",
        "--format=%cI",
        $commit
    ) | Select-Object -First 1).Trim()

    $manifest = [ordered]@{
        schemaVersion = 1
        tag = $shortTag
        commit = $commit
        tree = $rootTree
        commitTimestamp = $commitTimestamp
        archivePolicy = "tracked Git objects plus exact recursive submodule trees"
        submodules = @($submoduleRecords | Sort-Object { $_.path })
    }
    $manifestPath = Join-Path $temporaryDirectory "$archiveStem/SOURCE-MANIFEST.json"
    $manifestJson = $manifest | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText(
        $manifestPath,
        $manifestJson + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )

    $timestamp = [DateTimeOffset]::Parse($commitTimestamp).UtcDateTime
    Get-ChildItem -LiteralPath (Join-Path $temporaryDirectory $archiveStem) -Recurse -Force |
        ForEach-Object { $_.LastWriteTimeUtc = $timestamp }
    (Get-Item -LiteralPath (Join-Path $temporaryDirectory $archiveStem)).LastWriteTimeUtc = $timestamp

    Invoke-Tar -Arguments @(
        "-czf",
        $archivePath,
        "-C",
        $temporaryDirectory,
        $archiveStem
    )

    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(
        $checksumPath,
        "$hash  $([IO.Path]::GetFileName($archivePath))" + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )

    [ordered]@{
        archive = $archivePath
        sha256 = $hash
        checksum = $checksumPath
        tag = $shortTag
        commit = $commit
        submoduleCount = $submoduleRecords.Count
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
