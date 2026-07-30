# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseDirectory,

    [Parameter(Mandatory = $true)]
    [string]$MainApkPath,

    [Parameter(Mandatory = $true)]
    [string]$HangulApkPath,

    [Parameter(Mandatory = $true)]
    [string]$BuildMetadataPath,

    [Parameter(Mandatory = $true)]
    [string]$SourceArchivePath,

    [Parameter(Mandatory = $true)]
    [string]$SourceTag,

    [Parameter(Mandatory = $true)]
    [string]$SbomOutputPath,

    [Parameter(Mandatory = $true)]
    [string]$ProductName,

    [Parameter(Mandatory = $true)]
    [string]$ApplicationId,

    [Parameter(Mandatory = $true)]
    [string]$SourceRepositoryUrl,

    [Parameter(Mandatory = $true)]
    [string]$PrivacyPolicyUrl,

    [Parameter(Mandatory = $true)]
    [string]$SourceArchiveUrl,

    [Parameter(Mandatory = $true)]
    [string]$SigningCertificateSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedReleaseDirectory = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
$resolvedInputs = [ordered]@{
    MainApk = (Resolve-Path -LiteralPath $MainApkPath).Path
    HangulApk = (Resolve-Path -LiteralPath $HangulApkPath).Path
    BuildMetadata = (Resolve-Path -LiteralPath $BuildMetadataPath).Path
    SourceArchive = (Resolve-Path -LiteralPath $SourceArchivePath).Path
    SourceChecksum = (Resolve-Path -LiteralPath "$SourceArchivePath.sha256").Path
}
$resolvedSbomOutput = [IO.Path]::GetFullPath($SbomOutputPath)

foreach ($entry in $resolvedInputs.GetEnumerator()) {
    $parent = Split-Path -Parent $entry.Value
    if ($parent -ne $resolvedReleaseDirectory) {
        throw "$($entry.Key) must be staged directly in '$resolvedReleaseDirectory', got '$parent'."
    }
}
if ((Split-Path -Parent $resolvedSbomOutput) -ne $resolvedReleaseDirectory) {
    throw "The SBOM must be written directly into '$resolvedReleaseDirectory'."
}

& (Join-Path $PSScriptRoot "verify-source-archive.ps1") `
    -ArchivePath $resolvedInputs.SourceArchive `
    -Ref $SourceTag
if ($LASTEXITCODE -ne 0) {
    throw "Source archive verification failed."
}

& (Join-Path $PSScriptRoot "verify-release-licenses.ps1") `
    -ApkPath $resolvedInputs.MainApk
if ($LASTEXITCODE -ne 0) {
    throw "APK license verification failed."
}

& (Join-Path $PSScriptRoot "verify-release-secrets.ps1") `
    -ApkPath $resolvedInputs.MainApk
if ($LASTEXITCODE -ne 0) {
    throw "Main APK secret verification failed."
}

& (Join-Path $PSScriptRoot "verify-release-secrets.ps1") `
    -ApkPath $resolvedInputs.HangulApk
if ($LASTEXITCODE -ne 0) {
    throw "Hangul APK secret verification failed."
}

& (Join-Path $PSScriptRoot "verify-release-privacy.ps1") `
    -ApkPath $resolvedInputs.MainApk
if ($LASTEXITCODE -ne 0) {
    throw "Main APK privacy verification failed."
}

& (Join-Path $PSScriptRoot "verify-release-identity.ps1") `
    -MainApkPath $resolvedInputs.MainApk `
    -HangulApkPath $resolvedInputs.HangulApk `
    -ExpectedApplicationId $ApplicationId `
    -ExpectedProductName $ProductName `
    -ExpectedRepositoryUrl $SourceRepositoryUrl `
    -ExpectedPrivacyPolicyUrl $PrivacyPolicyUrl `
    -ExpectedSourceArchiveUrl $SourceArchiveUrl `
    -ExpectedSigningCertificateSha256 $SigningCertificateSha256
if ($LASTEXITCODE -ne 0) {
    throw "Release identity verification failed."
}

& (Join-Path $PSScriptRoot "generate-release-sbom.ps1") `
    -ApkPath $resolvedInputs.MainApk `
    -BuildMetadataPath $resolvedInputs.BuildMetadata `
    -SourceArchivePath $resolvedInputs.SourceArchive `
    -SourceRepositoryUrl $SourceRepositoryUrl `
    -SourceArchiveUrl $SourceArchiveUrl `
    -ProductName $ProductName `
    -ApplicationId $ApplicationId `
    -OutputPath $resolvedSbomOutput
if ($LASTEXITCODE -ne 0) {
    throw "SBOM generation failed."
}

$sbom = Get-Content -LiteralPath $resolvedSbomOutput -Raw | ConvertFrom-Json
if ($sbom.bomFormat -ne "CycloneDX" -or $sbom.specVersion -ne "1.6") {
    throw "Generated SBOM is not CycloneDX 1.6."
}

Write-Output "Release bundle verification: PASS"
Write-Output "Directory: $resolvedReleaseDirectory"
Write-Output "Source tag: $SourceTag"
Write-Output "SBOM: $resolvedSbomOutput"
