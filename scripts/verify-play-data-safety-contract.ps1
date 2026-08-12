# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

[CmdletBinding()]
param(
    [string]$SourceRoot = (Join-Path $PSScriptRoot "..")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = (Resolve-Path -LiteralPath $SourceRoot).Path
$contractPath = Join-Path $root "docs/independent-fork/play-data-safety-declaration.json"
$ssotPath = Join-Path $root "docs/independent-fork/privacy-data-safety.md"
$manifestPath = Join-Path $root "app/src/main/AndroidManifest.xml"
$baseStringsPath = Join-Path $root "app/src/main/res/values/strings.xml"
$koreanStringsPath = Join-Path $root "app/src/main/res/values-ko/strings.xml"
$noticePath = Join-Path $root "app/src/main/assets/legal/DATA-PRIVACY.txt"
$publicPolicyPath = Join-Path $root "site/privacy/index.html"

foreach ($path in @(
    $contractPath,
    $ssotPath,
    $manifestPath,
    $baseStringsPath,
    $koreanStringsPath,
    $noticePath,
    $publicPolicyPath
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required Data Safety contract input is missing: '$path'."
    }
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Actual,

        [Parameter(Mandatory = $true)]
        [object]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if ($Actual -ne $Expected) {
        throw "$Label must be '$Expected', got '$Actual'."
    }
}

function Assert-ContainsAll {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,

        [Parameter(Mandatory = $true)]
        [string[]]$Needles,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    foreach ($needle in $Needles) {
        if (-not $Text.Contains($needle, [StringComparison]::Ordinal)) {
            throw "$Label is missing required text '$needle'."
        }
    }
}

$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
Assert-Equal -Actual $contract.schemaVersion -Expected 1 -Label "Contract schema version"
Assert-Equal -Actual $contract.status -Expected "READY_FOR_PLAY_CONSOLE_DRAFT" -Label "Contract status"
Assert-Equal -Actual $contract.applicationId -Expected "net.chanpaca.saegeul" -Label "Application ID"
Assert-Equal -Actual $contract.publisher -Expected "Yun Chan" -Label "Publisher"
Assert-Equal -Actual $contract.contactEmail -Expected "yunchan@chanpaca.net" -Label "Contact email"
Assert-Equal -Actual $contract.privacyPolicyUrl `
    -Expected "https://saegul.chanpaca.net/privacy/" `
    -Label "Privacy policy URL"

foreach ($answer in @(
    "collectsOrSharesData",
    "allCollectedDataEncryptedInTransit"
)) {
    Assert-Equal -Actual $contract.globalAnswers.$answer -Expected $true -Label "Global answer $answer"
}
foreach ($answer in @(
    "deletionRequestMechanism",
    "developerAccount",
    "dataSale"
)) {
    Assert-Equal -Actual $contract.globalAnswers.$answer -Expected $false -Label "Global answer $answer"
}

$expectedProviders = @(
    "giphy",
    "github-raw-content",
    "google-fonts",
    "klipy",
    "oauth-provider",
    "openai-compatible-ai",
    "openai-compatible-speech",
    "wikimedia-commons"
)
$actualProviders = @($contract.releaseProviderSet.id | Sort-Object)
if (($actualProviders -join "`n") -ne (($expectedProviders | Sort-Object) -join "`n")) {
    throw "Release provider set does not match the locked Play declaration."
}

$expectedDataTypes = [ordered]@{
    approximate_location = @("Location", "Approximate location", "App functionality")
    in_app_search_history = @("App activity", "In-app search history", "App functionality")
    other_user_generated_content = @(
        "App activity",
        "Other user-generated content",
        "App functionality"
    )
    voice_or_sound_recordings = @("Audio files", "Voice or sound recordings", "App functionality")
    app_interactions = @("App activity", "App interactions", "App functionality", "Analytics")
    device_or_other_ids = @(
        "Device or other IDs",
        "Device or other IDs",
        "App functionality",
        "Analytics"
    )
}

Assert-Equal -Actual @($contract.dataTypes).Count -Expected $expectedDataTypes.Count -Label "Data type count"
foreach ($entry in $contract.dataTypes) {
    if (-not $expectedDataTypes.Contains($entry.id)) {
        throw "Unexpected Play data type '$($entry.id)'."
    }
    $expected = $expectedDataTypes[$entry.id]
    Assert-Equal -Actual $entry.playCategory -Expected $expected[0] -Label "$($entry.id) category"
    Assert-Equal -Actual $entry.playType -Expected $expected[1] -Label "$($entry.id) type"
    Assert-Equal -Actual $entry.collected -Expected $true -Label "$($entry.id) collected"
    Assert-Equal -Actual $entry.shared -Expected $true -Label "$($entry.id) shared"
    Assert-Equal -Actual $entry.processedEphemerally -Expected $false -Label "$($entry.id) ephemeral"
    Assert-Equal -Actual $entry.optional -Expected $true -Label "$($entry.id) optional"
    foreach ($purpose in $expected[2..($expected.Count - 1)]) {
        if ($entry.purposes -notcontains $purpose) {
            throw "$($entry.id) is missing purpose '$purpose'."
        }
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw
Assert-ContainsAll -Text $manifest -Label "Android manifest" -Needles @(
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO"
)
foreach ($forbiddenPermission in @(
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION"
)) {
    if ($manifest.Contains($forbiddenPermission, [StringComparison]::Ordinal)) {
        throw "Unexpected location permission '$forbiddenPermission' requires a contract revision."
    }
}

$baseStrings = Get-Content -LiteralPath $baseStringsPath -Raw
$koreanStrings = Get-Content -LiteralPath $koreanStringsPath -Raw
$notice = Get-Content -LiteralPath $noticePath -Raw
$publicPolicy = Get-Content -LiteralPath $publicPolicyPath -Raw
$ssot = Get-Content -LiteralPath $ssotPath -Raw

Assert-ContainsAll -Text $baseStrings -Label "Base disclosures" -Needles @(
    "IP address"
)
Assert-ContainsAll -Text $koreanStrings -Label "Korean disclosures" -Needles @(
    "IP 주소"
)
Assert-ContainsAll -Text $notice -Label "Embedded privacy notice" -Needles @(
    "IP address",
    "approximate location",
    "IP 주소",
    "대략적 위치"
)
Assert-ContainsAll -Text $publicPolicy -Label "Public privacy policy" -Needles @(
    "Yun Chan",
    "yunchan@chanpaca.net",
    "net.chanpaca.saegeul",
    "IP 주소",
    "대략적 위치",
    "앱 내 검색 기록",
    "기타 사용자 생성 콘텐츠",
    "음성 또는 소리 녹음",
    "GIPHY"
)
Assert-ContainsAll -Text $ssot -Label "Privacy SSOT" -Needles @(
    "play-data-safety-declaration.json",
    "READY_FOR_PLAY_CONSOLE_DRAFT",
    "삭제 요청 메커니즘",
    "공유: 예"
)

Write-Output "Play Data Safety contract verification: PASS"
Write-Output "Application ID: $($contract.applicationId)"
Write-Output "Providers: $($actualProviders.Count)"
Write-Output "Declared data types: $(@($contract.dataTypes).Count)"
Write-Output "Collected/shared data is optional and encrypted in transit: PASS"
