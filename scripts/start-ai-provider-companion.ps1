param(
    [int]$GatewayPort = 9211,
    [int]$TailscaleHttpsPort = 9210
)

$ErrorActionPreference = "Stop"
$companionPath = Join-Path $PSScriptRoot "ai-provider-companion.py"
$python = Get-Command python.exe -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command python -ErrorAction SilentlyContinue
}
if (-not $python) {
    throw "Python was not found. Install Python and sign in again."
}
if (-not (Test-Path -LiteralPath $companionPath -PathType Leaf)) {
    throw "AI companion was not found: $companionPath"
}

function Test-AiCompanion {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$GatewayPort/health" -Method Get -TimeoutSec 2
        return $health.status -eq "ok" -and $health.provider -eq "computer-cli"
    }
    catch {
        return $false
    }
}

# Keep the logon task alive so it can recover if Tailscale or either CLI starts later.
while ($true) {
    if (Test-AiCompanion) {
        Start-Sleep -Seconds 10
        continue
    }

    # Leave the localized display name to the UTF-8 Python source. Windows PowerShell 5.1 reads
    # BOM-less scripts as ANSI and would otherwise corrupt a Korean argument at logon.
    & $python.Source $companionPath --name $env:COMPUTERNAME --gateway-port $GatewayPort --tailscale-https-port $TailscaleHttpsPort

    Start-Sleep -Seconds 10
}
