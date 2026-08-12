# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$BuildMetadataPath,

    [Parameter(Mandatory = $true)]
    [string]$SourceArchivePath,

    [Parameter(Mandatory = $true)]
    [string]$SourceRepositoryUrl,

    [Parameter(Mandatory = $true)]
    [string]$SourceArchiveUrl,

    [Parameter(Mandatory = $true)]
    [string]$ProductName,

    [Parameter(Mandatory = $true)]
    [string]$ApplicationId,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
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

function Get-PackageUrl {
    param(
        [Parameter(Mandatory = $true)]
        [string]$UniqueId,

        [Parameter(Mandatory = $true)]
        [string]$Version
    )

    if ($UniqueId.Contains(":")) {
        $parts = $UniqueId.Split(":", 2)
        return "pkg:maven/$($parts[0])/$($parts[1])@$Version"
    }
    if ($UniqueId.Contains("/")) {
        return "pkg:github/$UniqueId@$Version"
    }
    return "pkg:generic/$([Uri]::EscapeDataString($UniqueId))@$([Uri]::EscapeDataString($Version))"
}

if (-not [Uri]::IsWellFormedUriString($SourceRepositoryUrl, [UriKind]::Absolute)) {
    throw "SourceRepositoryUrl must be an absolute URL."
}
if (-not [Uri]::IsWellFormedUriString($SourceArchiveUrl, [UriKind]::Absolute)) {
    throw "SourceArchiveUrl must be an absolute URL."
}
if ([string]::IsNullOrWhiteSpace($ProductName) -or [string]::IsNullOrWhiteSpace($ApplicationId)) {
    throw "ProductName and ApplicationId must be non-empty."
}

$resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$resolvedBuildMetadataPath = (Resolve-Path -LiteralPath $BuildMetadataPath).Path
$resolvedSourceArchivePath = (Resolve-Path -LiteralPath $SourceArchivePath).Path
$resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)

$checksumPath = "$resolvedSourceArchivePath.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Source archive checksum sidecar '$checksumPath' is missing."
}
$checksumLine = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
if ($checksumLine -notmatch "^([0-9a-fA-F]{64})\s{2}(.+)$") {
    throw "Source archive checksum sidecar has an invalid format."
}
$expectedArchiveHash = $Matches[1].ToLowerInvariant()
$actualArchiveHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSourceArchivePath
).Hash.ToLowerInvariant()
if ($expectedArchiveHash -ne $actualArchiveHash) {
    throw "Source archive SHA-256 mismatch."
}

$archiveEntries = @(& tar -tzf $resolvedSourceArchivePath)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to list the source archive."
}
$manifestEntries = @(
    $archiveEntries |
        Where-Object { $_ -match "^[^/]+/SOURCE-MANIFEST\.json$" }
)
if ($manifestEntries.Count -ne 1) {
    throw "The source archive must contain exactly one root SOURCE-MANIFEST.json."
}
$manifestText = (@(& tar -xOzf $resolvedSourceArchivePath $manifestEntries[0])) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read SOURCE-MANIFEST.json from the source archive."
}
$sourceManifest = $manifestText | ConvertFrom-Json
$buildMetadata = Get-Content -LiteralPath $resolvedBuildMetadataPath -Raw | ConvertFrom-Json
if ($buildMetadata.commitHash -ne $sourceManifest.commit) {
    throw "Binary/source commit mismatch: binary '$($buildMetadata.commitHash)', source '$($sourceManifest.commit)'."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkArchive = [IO.Compression.ZipFile]::OpenRead($resolvedApkPath)
try {
    $licenseEntryName = "res/raw/aboutlibraries.json"
    $licenseEntry = $apkArchive.GetEntry($licenseEntryName)
    if ($null -eq $licenseEntry) {
        $sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
            $env:ANDROID_SDK_ROOT
        } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
            $env:ANDROID_HOME
        } else {
            throw "ANDROID_SDK_ROOT or ANDROID_HOME is required to resolve optimized resources."
        }
        $apkAnalyzerName = if ($IsWindows) { "apkanalyzer.bat" } else { "apkanalyzer" }
        $apkAnalyzer = Join-Path $sdkRoot "cmdline-tools/latest/bin/$apkAnalyzerName"
        if (-not (Test-Path -LiteralPath $apkAnalyzer -PathType Leaf)) {
            throw "apkanalyzer was not found at '$apkAnalyzer'."
        }
        $resourcePath = @(
            & $apkAnalyzer resources value `
                --config default `
                --type raw `
                --name aboutlibraries `
                $resolvedApkPath 2>&1
        )
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to resolve the optimized AboutLibraries resource:`n$($resourcePath -join "`n")"
        }
        $licenseEntryName = ($resourcePath -join "`n").Trim()
        if ($licenseEntryName -notmatch "^res/[^/]+\.json$") {
            throw "Unexpected AboutLibraries resource path '$licenseEntryName'."
        }
        $licenseEntry = $apkArchive.GetEntry($licenseEntryName)
    }
    if ($null -eq $licenseEntry -or $licenseEntry.Length -eq 0) {
        throw "aboutlibraries.json is missing or empty in the APK."
    }
    $reader = [IO.StreamReader]::new($licenseEntry.Open())
    try {
        $licenseMetadata = $reader.ReadToEnd() | ConvertFrom-Json
    } finally {
        $reader.Dispose()
    }
} finally {
    $apkArchive.Dispose()
}

$licenses = @(
    $licenseMetadata.licenses.PSObject.Properties |
        ForEach-Object { $_.Value }
)
$licenseLookup = @{}
foreach ($license in $licenses) {
    $licenseLookup[$license.hash] = $license
    $spdxId = Get-OptionalPropertyValue -InputObject $license -Name "spdxId"
    if (-not [string]::IsNullOrWhiteSpace($spdxId)) {
        $licenseLookup[$spdxId] = $license
    }
}

$components = [Collections.Generic.List[object]]::new()
foreach ($library in @($licenseMetadata.libraries | Sort-Object uniqueId, artifactVersion)) {
    $componentLicenses = [Collections.Generic.List[object]]::new()
    foreach ($licenseId in @($library.licenses)) {
        if (-not $licenseLookup.ContainsKey($licenseId)) {
            throw "Library '$($library.uniqueId)' references missing license '$licenseId'."
        }
        $license = $licenseLookup[$licenseId]
        $spdxId = Get-OptionalPropertyValue -InputObject $license -Name "spdxId"
        if (-not [string]::IsNullOrWhiteSpace($spdxId)) {
            $componentLicenses.Add([ordered]@{ license = [ordered]@{ id = $spdxId } })
        } else {
            $componentLicenses.Add([ordered]@{ license = [ordered]@{ name = $license.name } })
        }
    }

    $version = if ([string]::IsNullOrWhiteSpace($library.artifactVersion)) {
        "unknown"
    } else {
        $library.artifactVersion
    }
    $packageUrl = Get-PackageUrl -UniqueId $library.uniqueId -Version $version
    $component = [ordered]@{
        type = "library"
        "bom-ref" = $packageUrl
        group = if ($library.uniqueId.Contains(":")) {
            $library.uniqueId.Split(":", 2)[0]
        } else {
            ""
        }
        name = $library.uniqueId
        version = $version
        licenses = @($componentLicenses)
        purl = $packageUrl
        properties = @(
            [ordered]@{
                name = "aboutlibraries.uniqueId"
                value = $library.uniqueId
            }
        )
    }
    $website = Get-OptionalPropertyValue -InputObject $library -Name "website"
    if (-not [string]::IsNullOrWhiteSpace($website)) {
        $component.externalReferences = @(
            [ordered]@{ type = "website"; url = $website }
        )
    }
    $components.Add($component)
}

foreach ($submodule in @($sourceManifest.submodules | Sort-Object path)) {
    $components.Add(
        [ordered]@{
            type = "library"
            "bom-ref" = "git:$($submodule.path)@$($submodule.commit)"
            name = $submodule.path
            version = $submodule.commit
            externalReferences = @(
                [ordered]@{ type = "vcs"; url = $submodule.url }
            )
            properties = @(
                [ordered]@{
                    name = "git.commit"
                    value = $submodule.commit
                },
                [ordered]@{
                    name = "git.path"
                    value = $submodule.path
                }
            )
        }
    )
}

$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApkPath).Hash.ToLowerInvariant()
$applicationRef = "pkg:apk/$ApplicationId@$($buildMetadata.versionName)"
$bom = [ordered]@{
    bomFormat = "CycloneDX"
    specVersion = "1.6"
    serialNumber = "urn:uuid:$([Guid]::NewGuid())"
    version = 1
    metadata = [ordered]@{
        timestamp = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        component = [ordered]@{
            type = "application"
            "bom-ref" = $applicationRef
            name = $ProductName
            version = $buildMetadata.versionName
            hashes = @(
                [ordered]@{ alg = "SHA-256"; content = $apkHash }
            )
            purl = $applicationRef
            externalReferences = @(
                [ordered]@{ type = "vcs"; url = $SourceRepositoryUrl },
                [ordered]@{
                    type = "distribution"
                    url = [Uri]::new($resolvedApkPath).AbsoluteUri
                },
                [ordered]@{
                    type = "distribution"
                    url = $SourceArchiveUrl
                }
            )
            properties = @(
                [ordered]@{
                    name = "android.applicationId"
                    value = $ApplicationId
                },
                [ordered]@{
                    name = "git.commit"
                    value = $sourceManifest.commit
                },
                [ordered]@{
                    name = "git.tag"
                    value = $sourceManifest.tag
                },
                [ordered]@{
                    name = "source.archive.sha256"
                    value = $actualArchiveHash
                }
            )
        }
    }
    components = @($components)
}

$outputDirectory = Split-Path -Parent $resolvedOutputPath
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}
$json = $bom | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText(
    $resolvedOutputPath,
    $json + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)

Write-Output "CycloneDX SBOM: CREATED"
Write-Output "Output: $resolvedOutputPath"
Write-Output "Components: $($components.Count)"
Write-Output "Binary commit: $($buildMetadata.commitHash)"
Write-Output "Source tag: $($sourceManifest.tag)"
Write-Output "Source archive SHA-256: $actualArchiveHash"
