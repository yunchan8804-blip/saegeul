#requires -Version 5.1
<#
.SYNOPSIS
공개 생성 문장 은행의 읽기 전용 사후 평가를 순차 실행하고 기준 로그와 비교한다.

.DESCRIPTION
두 GemmaUtilityDeviceTest 메서드를 순차 실행한다. 생성, 설치, 설정 변경, ADB 연결은 수행하지 않는다.
각 실행의 instrumentation 원문과 종료 코드를 보존하고, 실패하면 다음 실행을 시작하지 않는다.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BaselineDirectory,

    [ValidateNotNullOrEmpty()]
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-RequiredValue {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "$Context 에 필수 필드 '$Name'가 없습니다."
    }
    return $property.Value
}

function Assert-LogSucceeded {
    param(
        [Parameter(Mandatory = $true)][string]$LogText,
        [Parameter(Mandatory = $true)][string]$Context
    )

    if ([regex]::IsMatch($LogText, 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED')) {
        throw "$Context instrumentation 로그에 실패 표식이 있습니다."
    }
    if ([regex]::Matches($LogText, 'OK \(1 test\)').Count -ne 1) {
        throw "$Context instrumentation 로그에 정확히 한 개의 'OK (1 test)' 결과가 없습니다."
    }
}

function Get-FinalEvidenceSummary {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$EvidenceKey,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $logText = [System.IO.File]::ReadAllText($LogPath)
    Assert-LogSucceeded -LogText $logText -Context $Context
    $pattern = '(?m)^INSTRUMENTATION_STATUS:\s+' + [regex]::Escape($EvidenceKey) + '=(\{.*\})\s*$'
    $evidenceMatches = [regex]::Matches($logText, $pattern)
    $summaries = @()
    foreach ($match in $evidenceMatches) {
        try {
            $candidate = $match.Groups[1].Value | ConvertFrom-Json
        }
        catch {
            throw "$Context evidence JSON을 읽을 수 없습니다: $($_.Exception.Message)"
        }
        if ($candidate.PSObject.Properties.Name -notcontains 'event' -and $null -ne $candidate.PSObject.Properties['independent']) {
            $summaries += $candidate
        }
    }
    if ($summaries.Count -ne 1) {
        throw "$Context 최종 independent summary가 정확히 하나가 아닙니다: $($summaries.Count)"
    }
    return $summaries[0]
}

function Assert-ReadOnlySummary {
    param(
        [Parameter(Mandatory = $true)]$Summary,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $beforeCount = [int](Get-RequiredValue -Object $Summary -Name 'beforeCount' -Context $Context)
    $afterCount = [int](Get-RequiredValue -Object $Summary -Name 'afterCount' -Context $Context)
    if ($beforeCount -ne $afterCount) {
        throw "$Context 읽기 전용 평가가 은행 수를 변경했습니다: $beforeCount -> $afterCount"
    }

    $sequenceBefore = [long](Get-RequiredValue -Object $Summary -Name 'openSequenceBefore' -Context $Context)
    $sequenceAfter = [long](Get-RequiredValue -Object $Summary -Name 'openSequenceAfter' -Context $Context)
    if ($sequenceBefore -ne $sequenceAfter) {
        throw "$Context 읽기 전용 평가가 공개 순번을 변경했습니다: $sequenceBefore -> $sequenceAfter"
    }

    foreach ($stateName in @('enabledBefore', 'enabledAfter', 'nativeGeneratingBefore', 'nativeGeneratingAfter')) {
        if ((Get-RequiredValue -Object $Summary -Name $stateName -Context $Context) -ne $false) {
            throw "$Context 상태 '$stateName'가 false가 아닙니다."
        }
    }
}

function Assert-FixtureMatchesBaseline {
    param(
        [Parameter(Mandatory = $true)]$Baseline,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Context
    )

    foreach ($name in @('fixtureSet', 'fixtureSha256', 'completionDenominator', 'terminalBoundaryDenominator', 'independentCount')) {
        $expected = Get-RequiredValue -Object $Baseline -Name $name -Context 'baseline'
        $observed = Get-RequiredValue -Object $Actual -Name $name -Context $Context
        if ($observed -ne $expected) {
            throw "$Context '$name'가 baseline과 다릅니다: expected=$expected actual=$observed"
        }
    }

    $expectedInputs = @(Get-RequiredValue -Object $Baseline -Name 'independent' -Context 'baseline')
    $actualInputs = @(Get-RequiredValue -Object $Actual -Name 'independent' -Context $Context)
    if ($actualInputs.Count -ne $expectedInputs.Count) {
        throw "$Context independent 배열 수가 baseline과 다릅니다: expected=$($expectedInputs.Count) actual=$($actualInputs.Count)"
    }
    for ($index = 0; $index -lt $expectedInputs.Count; $index++) {
        foreach ($name in @('id', 'caseKind', 'denominator', 'inputSha256')) {
            $expected = Get-RequiredValue -Object $expectedInputs[$index] -Name $name -Context "baseline independent[$index]"
            $observed = Get-RequiredValue -Object $actualInputs[$index] -Name $name -Context "$Context independent[$index]"
            if ($observed -ne $expected) {
                throw "$Context independent[$index] '$name'가 baseline과 다릅니다: expected=$expected actual=$observed"
            }
        }
    }
}

function Get-MeasurementSummary {
    param(
        [Parameter(Mandatory = $true)]$Evidence
    )

    $inputs = @(Get-RequiredValue -Object $Evidence -Name 'independent' -Context 'evidence')
    $completionInputs = @($inputs | Where-Object { $_.caseKind -eq 'completion' })
    $generatedExposureCount = @($completionInputs | Where-Object { @($_.generatedPredictions).Count -gt 0 }).Count
    $bankExposureCount = @($completionInputs | Where-Object { [int]$_.candidateCount -gt 0 }).Count

    return [PSCustomObject]@{
        fixtureSet = Get-RequiredValue -Object $Evidence -Name 'fixtureSet' -Context 'evidence'
        fixtureSha256 = Get-RequiredValue -Object $Evidence -Name 'fixtureSha256' -Context 'evidence'
        completionDenominator = [int](Get-RequiredValue -Object $Evidence -Name 'completionDenominator' -Context 'evidence')
        generatedPredictionExposureCount = $generatedExposureCount
        bankCandidateExposureCount = $bankExposureCount
        bankCount = [int](Get-RequiredValue -Object $Evidence -Name 'afterCount' -Context 'evidence')
        openSequence = [long](Get-RequiredValue -Object $Evidence -Name 'openSequenceAfter' -Context 'evidence')
    }
}

function Write-CommandRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    [PSCustomObject]@{
        executable = $Executable
        arguments = @($Arguments)
    } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-Measurement {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ClassName,
        [Parameter(Mandatory = $true)][string]$EvidenceKey,
        [Parameter(Mandatory = $true)]$Baseline
    )

    $logPath = Join-Path $OutputDirectory "post-$Name.log"
    $exitPath = Join-Path $OutputDirectory "post-$Name.exit"
    $commandPath = Join-Path $OutputDirectory "post-$Name.command.txt"
    $arguments = @(
        '-s', $DeviceSerial,
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $ClassName,
        'net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner'
    )

    Write-CommandRecord -Path $commandPath -Executable $AdbPath -Arguments $arguments
    & $AdbPath @arguments *> $logPath
    $exitCode = $LASTEXITCODE
    Set-Content -LiteralPath $exitPath -Value $exitCode -Encoding UTF8
    if ($exitCode -ne 0) {
        throw "$Name instrumentation adb 종료 코드가 0이 아닙니다: $exitCode"
    }

    $actual = Get-FinalEvidenceSummary -LogPath $logPath -EvidenceKey $EvidenceKey -Context $Name
    Assert-ReadOnlySummary -Summary $actual -Context $Name
    Assert-FixtureMatchesBaseline -Baseline $Baseline -Actual $actual -Context $Name
    return $actual
}

$baselineIndependentPath = Join-Path $BaselineDirectory 'utility-independent-retry.log'
$baselineHeldOutPath = Join-Path $BaselineDirectory 'utility-heldout-retry.log'
foreach ($path in @($baselineIndependentPath, $baselineHeldOutPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "필수 baseline 로그가 없습니다: $path"
    }
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb 실행 파일이 없습니다: $AdbPath"
}

if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
}
if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    throw "OutputDirectory가 디렉터리가 아닙니다: $OutputDirectory"
}

$lockPath = Join-Path $OutputDirectory '.measurement.lock'
try {
    $lockStream = [System.IO.File]::Open(
        $lockPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
}
catch [System.IO.IOException] {
    throw "OutputDirectory가 이미 사용되었거나 이전 사후평가 흔적이 있습니다. 새 OutputDirectory로 다시 실행하세요: $OutputDirectory"
}

try {
    $outputPaths = @(
    'post-independent.log', 'post-independent.exit', 'post-independent.command.txt',
    'post-heldout.log', 'post-heldout.exit', 'post-heldout.command.txt',
    'post-summary.json'
) | ForEach-Object { Join-Path $OutputDirectory $_ }
    foreach ($path in $outputPaths) {
        if (Test-Path -LiteralPath $path) {
            throw "기존 사후평가 파일을 덮어쓸 수 없습니다: $path"
        }
    }

    $baselineIndependent = Get-FinalEvidenceSummary -LogPath $baselineIndependentPath -EvidenceKey 'gemmaUtilityEvidence' -Context 'baseline independent'
    $baselineHeldOut = Get-FinalEvidenceSummary -LogPath $baselineHeldOutPath -EvidenceKey 'gemmaHeldOutUtilityEvidence' -Context 'baseline heldout'
    Assert-ReadOnlySummary -Summary $baselineIndependent -Context 'baseline independent'
    Assert-ReadOnlySummary -Summary $baselineHeldOut -Context 'baseline heldout'

    $stateOutput = & $AdbPath -s $DeviceSerial get-state 2>&1
    $stateExitCode = $LASTEXITCODE
    if ($stateExitCode -ne 0 -or (($stateOutput | Out-String).Trim() -ne 'device')) {
        throw "지정한 기기가 adb get-state=device 상태가 아닙니다. instrumentation을 실행하지 않았습니다."
    }

    $postIndependent = Invoke-Measurement -Name 'independent' -ClassName 'org.fcitx.fcitx5.android.GemmaUtilityDeviceTest#measureIndependentPublicInputs' -EvidenceKey 'gemmaUtilityEvidence' -Baseline $baselineIndependent
    $postHeldOut = Invoke-Measurement -Name 'heldout' -ClassName 'org.fcitx.fcitx5.android.GemmaUtilityDeviceTest#measureHeldOutPublicInputs' -EvidenceKey 'gemmaHeldOutUtilityEvidence' -Baseline $baselineHeldOut

    if ([int]$postIndependent.afterCount -ne [int]$postHeldOut.afterCount) {
        throw "두 사후평가의 은행 수가 다릅니다: $($postIndependent.afterCount) vs $($postHeldOut.afterCount)"
    }
    if ([long]$postIndependent.openSequenceAfter -ne [long]$postHeldOut.openSequenceAfter) {
        throw "두 사후평가의 공개 순번이 다릅니다: $($postIndependent.openSequenceAfter) vs $($postHeldOut.openSequenceAfter)"
    }

    [PSCustomObject]@{
        independent = Get-MeasurementSummary -Evidence $postIndependent
        heldOut = Get-MeasurementSummary -Evidence $postHeldOut
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'post-summary.json') -Encoding UTF8
}
finally {
    $lockStream.Dispose()
}
