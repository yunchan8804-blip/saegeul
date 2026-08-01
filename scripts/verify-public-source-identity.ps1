# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding(DefaultParameterSetName = "SourceRoot")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "SourceRoot")]
    [string]$SourceRoot,

    [Parameter(Mandatory = $true, ParameterSetName = "SourceArchive")]
    [string]$SourceArchivePath,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedProductName,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedKoreanProductName,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*){2,}$")]
    [string]$ExpectedApplicationId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$ExpectedRepositoryUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$ExpectedPrivacyPolicyUrl
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-TextFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required public source file '$Path' is missing."
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content,

        [Parameter(Mandatory = $true)]
        [string]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Surface
    )

    if (-not $Content.Contains($Expected, [StringComparison]::Ordinal)) {
        throw "Public source identity '$Expected' is missing from '$Surface'."
    }
}

function Test-PublicSourceIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ResolvedSourceRoot
    )

    $readmePath = Join-Path $ResolvedSourceRoot "README.md"
    $siteIndexPath = Join-Path $ResolvedSourceRoot "site/index.html"
    $privacyPath = Join-Path $ResolvedSourceRoot "site/privacy/index.html"
    $productIdentityPath = Join-Path $ResolvedSourceRoot `
        "build-logic/convention/src/main/kotlin/ProductIdentity.kt"
    $settingsPath = Join-Path $ResolvedSourceRoot "settings.gradle.kts"

    $readme = Get-TextFile -Path $readmePath
    $siteIndex = Get-TextFile -Path $siteIndexPath
    $privacy = Get-TextFile -Path $privacyPath
    $productIdentity = Get-TextFile -Path $productIdentityPath
    $settings = Get-TextFile -Path $settingsPath

    foreach ($required in @(
        $ExpectedProductName,
        $ExpectedKoreanProductName,
        $ExpectedApplicationId,
        $ExpectedRepositoryUrl,
        $ExpectedPrivacyPolicyUrl,
        "비공식 독립 포크",
        "제휴",
        "보증"
    )) {
        Assert-Contains -Content $readme -Expected $required -Surface "README.md"
    }
    foreach ($required in @(
        $ExpectedProductName,
        $ExpectedKoreanProductName,
        $ExpectedRepositoryUrl
    )) {
        Assert-Contains -Content $siteIndex -Expected $required -Surface "site/index.html"
    }
    foreach ($required in @(
        $ExpectedProductName,
        $ExpectedKoreanProductName
    )) {
        Assert-Contains -Content $privacy -Expected $required -Surface "site/privacy/index.html"
    }
    Assert-Contains -Content $productIdentity `
        -Expected "const val applicationId = `"$ExpectedApplicationId`"" `
        -Surface "ProductIdentity.kt"

    $publicSurfacePaths = @($readmePath)
    foreach ($relativeDirectory in @("site", ".github/workflows")) {
        $directory = Join-Path $ResolvedSourceRoot $relativeDirectory
        if (Test-Path -LiteralPath $directory -PathType Container) {
            $publicSurfacePaths += @(
                Get-ChildItem -LiteralPath $directory -Recurse -File |
                    ForEach-Object { $_.FullName }
            )
        }
    }

    $forbiddenPatterns = [ordered]@{
        "official GitHub release" = "https://github.com/fcitx5-android/fcitx5-android/releases"
        "official F-Droid package" = "https://f-droid.org/packages/org.fcitx.fcitx5.android"
        "official Play package" = "https://play.google.com/store/apps/details?id=org.fcitx.fcitx5.android"
        "official Jenkins distribution" = "https://jenkins.fcitx-im.org/job/android/job/fcitx5-android"
        "official publisher bot" = "fcitx5-android-bot"
        "old public application ID" = "org.fcitx.fcitx5.android"
        "old independent application ID" = "kr.twentyoz.saegeul"
        "old independent repository" = "github.com/yunchan8804/saegeul"
        "old independent website" = "saegeul.twentyoz.kr"
    }
    $violations = [Collections.Generic.List[string]]::new()
    foreach ($path in $publicSurfacePaths | Sort-Object -Unique) {
        $content = Get-Content -LiteralPath $path -Raw
        foreach ($entry in $forbiddenPatterns.GetEnumerator()) {
            if ($content.Contains($entry.Value, [StringComparison]::Ordinal)) {
                $relativePath = [IO.Path]::GetRelativePath($ResolvedSourceRoot, $path).Replace("\", "/")
                $violations.Add("${relativePath}: $($entry.Key)")
            }
        }
    }
    if ($violations.Count -ne 0) {
        throw "Forbidden upstream public identity remains:`n$($violations -join "`n")"
    }

    $pluginIncludes = @(
        [regex]::Matches($settings, 'include\("(:plugin:[^"]+)"\)') |
            ForEach-Object { $_.Groups[1].Value }
    )
    if ($pluginIncludes.Count -ne 1 -or $pluginIncludes[0] -ne ":plugin:hangul") {
        throw "The first independent product build must include only :plugin:hangul; got '$($pluginIncludes -join ', ')'."
    }
    if ($settings -match "fcitx5-chinese-addons") {
        throw "fcitx5-chinese-addons is still included in the product build graph."
    }
    if (Test-Path -LiteralPath (Join-Path $ResolvedSourceRoot "app/org.fcitx.fcitx5.android.yml")) {
        throw "The old public F-Droid metadata file is still present."
    }

    Write-Output "Public source identity audit: PASS"
    Write-Output "Product: $ExpectedKoreanProductName ($ExpectedProductName)"
    Write-Output "Application ID: $ExpectedApplicationId"
    Write-Output "Repository: $ExpectedRepositoryUrl"
    Write-Output "Privacy policy: $ExpectedPrivacyPolicyUrl"
    Write-Output "Public files scanned: $($publicSurfacePaths.Count)"
    Write-Output "Product plugins: $($pluginIncludes -join ', ')"
    Write-Output "Forbidden upstream public identities: 0"
}

$temporaryDirectory = $null
try {
    if ($PSCmdlet.ParameterSetName -eq "SourceArchive") {
        $resolvedArchive = (Resolve-Path -LiteralPath $SourceArchivePath).Path
        $temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $temporaryDirectory = Join-Path $temporaryBase (
            "public-source-identity-" + [Guid]::NewGuid().ToString("N")
        )
        [IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
        & tar -xzf $resolvedArchive -C $temporaryDirectory
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to extract '$resolvedArchive'."
        }
        $roots = @(Get-ChildItem -LiteralPath $temporaryDirectory -Directory)
        if ($roots.Count -ne 1) {
            throw "Source archive must contain exactly one root directory, found $($roots.Count)."
        }
        $resolvedRoot = $roots[0].FullName
    } else {
        $resolvedRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
    }

    Test-PublicSourceIdentity -ResolvedSourceRoot $resolvedRoot
} finally {
    if ($null -ne $temporaryDirectory) {
        $temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $resolvedTemporaryDirectory = [IO.Path]::GetFullPath($temporaryDirectory)
        if (
            (Test-Path -LiteralPath $resolvedTemporaryDirectory) -and
            $resolvedTemporaryDirectory.StartsWith(
                $temporaryBase,
                [StringComparison]::OrdinalIgnoreCase
            )
        ) {
            Remove-Item -LiteralPath $resolvedTemporaryDirectory -Recurse -Force
        }
    }
}
