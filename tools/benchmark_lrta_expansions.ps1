[CmdletBinding()]
param(
    [int[]] $Expansions = @(1, 4, 8, 16, 32),
    [string] $Manifest = 'maps/path-suite-maps.txt',
    [string] $NamePattern = '^PathSuite\d+_(Combs|Spirals)$',
    [int] $Limit = 0,
    [string] $OutputRoot = 'analysis/lrta-expansion-sweep',
    [string] $ReplayPrefix = 'lrta-bench',
    [switch] $ContinueOnError
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourcePath = Join-Path $projectRoot 'src/lrta/LearningRealTimeAStar.java'
$manifestPath = Join-Path $projectRoot $Manifest
$originalSource = [System.IO.File]::ReadAllText($sourcePath)
$maps = @(Get-Content -LiteralPath $manifestPath |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and -not $_.StartsWith('#') -and $_ -match $NamePattern })
if ($Limit -gt 0) {
    $maps = @($maps | Select-Object -First $Limit)
}
if ($maps.Count -eq 0) {
    throw "No maps in $manifestPath matched $NamePattern"
}

$wrapper = Join-Path $projectRoot 'gradlew.bat'
$failures = @()
try {
    foreach ($expansionCount in $Expansions) {
        if ($expansionCount -lt 1 -or $expansionCount -gt 63) {
            throw "Expansion count must be in [1, 63]: $expansionCount"
        }
        $patchedSource = $originalSource -replace
            'public static final int MAX_EXPANSIONS = \d+;',
            "public static final int MAX_EXPANSIONS = $expansionCount;"
        [System.IO.File]::WriteAllText($sourcePath, $patchedSource)

        foreach ($mapName in $maps) {
            $replay = "matches/$ReplayPrefix-e$expansionCount-on-$mapName.bc26"
            $analysisOutput = "$OutputRoot/deterministic-e$expansionCount/$mapName"
            Write-Host "[e=$expansionCount] lrtabench on $mapName"
            & $wrapper -p $projectRoot benchmarkPaths `
                '-PteamA=lrtabench' `
                '-PteamB=lrtabench' `
                "-Pmaps=$mapName" `
                "-Preplay=$replay" `
                "-PanalysisReplay=$replay" `
                "-PanalysisMap=$mapName" `
                "-PanalysisOutput=$analysisOutput" `
                '-PoutputVerbose=false'
            if ($LASTEXITCODE -ne 0) {
                $failures += "e=$expansionCount map=$mapName exit=$LASTEXITCODE"
                if (-not $ContinueOnError) {
                    throw $failures[-1]
                }
            }
        }
    }
} finally {
    [System.IO.File]::WriteAllText($sourcePath, $originalSource)
}

if ($failures.Count -gt 0) {
    throw "Failed benchmarks:`n$($failures -join "`n")"
}
