[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    & .\gradlew.bat generateConcaveMapSuite
    if ($LASTEXITCODE -ne 0) { throw 'Concave map generation failed.' }
    & .\tools\benchmark_path_suite.ps1 `
        -Bots concavertaabench,concavelrtabench,rtaabench,lrtabench,concavebugnavbench,concavebug2bench,bugnavbench,bug2bench `
        -Manifest maps/concave-suite-maps.txt -VerboseBots `
        -OutputRoot analysis/concavity -ReplayPrefix concavity -FailOnBytecodeLimit
    & node tools/analyze_concavity.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Concavity analysis failed.' }
} finally {
    Pop-Location
}
