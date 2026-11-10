[CmdletBinding()]
param(
    [string[]] $Bots = @('pathplanner', 'classicbug2', 'rtaa'),
    [string] $Manifest = 'maps/path-suite-maps.txt',
    [int] $Start = 1,
    [int] $Limit = 0,
    [switch] $DryRun,
    [switch] $ContinueOnError
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$manifestPath = if ([System.IO.Path]::IsPathRooted($Manifest)) {
    $Manifest
} else {
    Join-Path $projectRoot $Manifest
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Missing $manifestPath. Run .\gradlew.bat generatePathfindingMapSuite first."
}

$maps = @(Get-Content -LiteralPath $manifestPath |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and -not $_.StartsWith('#') } |
    Select-Object -Skip ([Math]::Max(0, $Start - 1)))
if ($Limit -gt 0) {
    $maps = @($maps | Select-Object -First $Limit)
}

$wrapper = Join-Path $projectRoot 'gradlew.bat'
$failures = @()
$total = $Bots.Count * $maps.Count
$completed = 0

foreach ($bot in $Bots) {
    foreach ($mapName in $maps) {
        $completed++
        $analysisOutput = "analysis/suite/$bot/$mapName"
        $gradleArguments = @(
            'benchmarkPaths',
            "-PteamA=$bot",
            "-PteamB=$bot",
            "-Pmaps=$mapName",
            "-PanalysisMap=$mapName",
            "-PanalysisOutput=$analysisOutput",
            '-PoutputVerbose=false'
        )

        Write-Host ("[{0:D3}/{1:D3}] {2} on {3}" -f
                $completed, $total, $bot, $mapName)
        if ($DryRun) {
            Write-Host ($wrapper + ' ' + ($gradleArguments -join ' '))
            continue
        }

        & $wrapper @gradleArguments
        if ($LASTEXITCODE -ne 0) {
            $failures += [pscustomobject]@{
                Bot = $bot
                Map = $mapName
                ExitCode = $LASTEXITCODE
            }
            if (-not $ContinueOnError) {
                exit $LASTEXITCODE
            }
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Error ("Failed benchmarks:`n" + ($failures | Format-Table | Out-String))
    exit 1
}
