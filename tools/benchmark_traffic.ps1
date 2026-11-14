[CmdletBinding()]
param([switch] $SkipGeneration)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    if (-not $SkipGeneration) {
        & .\gradlew.bat generateTrafficMapSuite
        if ($LASTEXITCODE -ne 0) { throw 'Traffic map generation failed.' }
    }
    & node tools/analyze_traffic.mjs analysis/traffic reports/traffic --feasibility-only
    if ($LASTEXITCODE -ne 0) { throw 'Traffic schedule feasibility check failed.' }
    $bots = @('bugnav', 'rtaa', 'lrta') | ForEach-Object {
        $planner = $_
        @('solo', 'solob', 'platoon8', 'opposed2', 'opposed8') | ForEach-Object { "traffic$planner$_" }
    }
    & .\tools\benchmark_path_suite.ps1 -Bots $bots -Manifest maps/traffic-suite-maps.txt `
        -VerboseBots -OutputRoot analysis/traffic -ReplayPrefix traffic -FailOnBytecodeLimit
    & node tools/analyze_traffic.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Traffic analysis failed.' }
} finally {
    Pop-Location
}
