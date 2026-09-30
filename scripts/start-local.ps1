$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$nodePath = if ($nodeCommand) { $nodeCommand.Source } else { $null }
function Test-NodeVersion($candidate) {
    if (-not $candidate -or -not (Test-Path -LiteralPath $candidate)) { return $false }
    $version = [version]((& $candidate --version).TrimStart('v'))
    return $version -ge [version]'22.12.0'
}
if (-not (Test-NodeVersion $nodePath)) {
    $nodePath = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
}
if (-not (Test-NodeVersion $nodePath)) { throw 'Please install Node.js 24 LTS and retry.' }
$npmCommand = Get-Command npm.cmd -ErrorAction Stop
$npmCli = Join-Path (Split-Path -Parent $npmCommand.Source) 'node_modules\npm\bin\npm-cli.js'
if (-not (Test-Path -LiteralPath $npmCli)) { throw 'npm CLI not found. Please install Node.js 24 LTS with npm.' }
$env:Path = (Split-Path -Parent $nodePath) + ';' + $env:Path
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules'))) {
    & $nodePath $npmCli ci
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& $nodePath $npmCli run dev
exit $LASTEXITCODE

