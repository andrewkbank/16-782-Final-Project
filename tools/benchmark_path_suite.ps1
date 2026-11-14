[CmdletBinding()]
param(
    [string[]] $Bots = @('pathplanner', 'classicbug2', 'rtaa', 'lrta'),
    [string] $Manifest = 'maps/path-suite-maps.txt',
    [switch] $AllMaps,
    [switch] $VerboseBots,
    [string] $OutputRoot = 'analysis/suite',
    [string] $ReplayPrefix = '',
    [switch] $FailOnBytecodeLimit,
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

if (-not $AllMaps -and -not (Test-Path -LiteralPath $manifestPath)) {
    throw "Missing $manifestPath. Run .\gradlew.bat generatePathfindingMapSuite first."
}

$maps = if ($AllMaps) {
    @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'maps') -Filter '*.map26' |
        Sort-Object Name | ForEach-Object { $_.BaseName })
} else {
    @(Get-Content -LiteralPath $manifestPath |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and -not $_.StartsWith('#') })
}
$maps = @($maps | Select-Object -Skip ([Math]::Max(0, $Start - 1)))
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
        $analysisOutput = "$OutputRoot/$bot/$mapName"
        $gradleArguments = @(
            '-p', $projectRoot,
            'benchmarkPaths',
            "-PteamA=$bot",
            "-PteamB=$bot",
            "-Pmaps=$mapName",
            "-PanalysisMap=$mapName",
            "-PanalysisOutput=$analysisOutput",
            "-PoutputVerbose=$($VerboseBots.IsPresent.ToString().ToLowerInvariant())"
        )
        if ($ReplayPrefix) {
            $replay = "matches/$ReplayPrefix-$bot-on-$mapName.bc26"
            $gradleArguments += "-Preplay=$replay", "-PanalysisReplay=$replay"
        }

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
        elseif ($FailOnBytecodeLimit) {
            $analysisDirectory = if ([System.IO.Path]::IsPathRooted($analysisOutput)) {
                $analysisOutput
            } else {
                Join-Path $projectRoot $analysisOutput
            }
            $trace = Join-Path $analysisDirectory 'path-trace.csv'
            if (Import-Csv -LiteralPath $trace | Where-Object { [int] $_.bytecodes_used -ge 17500 }) {
                throw "Bytecode limit reached: $bot on $mapName (see $trace)"
            }
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Error ("Failed benchmarks:`n" + ($failures | Format-Table | Out-String))
    exit 1
}
