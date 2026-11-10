# Battlecode 2026 Path Planning

This project evaluates low-bytecode path planners for MIT Battlecode 2026. It
contains three minimal bots with identical high-level behavior, reproducible test
maps, and a replay analyzer that compares observed routes with optimal static-map
paths.

Each bot uses one stationary rat king and one baby rat:

- The king stays on its cheese mine, collects nearby cheese, and builds exactly
  one baby rat.
- The baby chooses deterministic random destinations and delegates movement to
  the selected path planner.
- Movement explicitly turns first and calls `moveForward()` only after checking
  legality.

The project currently uses Battlecode engine `1.2.6`, client `1.0.7`, and Java
21.

## Path planners

### IvanGeffner-derived BugNav (`pathplanner`)

`src/pathplanner/BugNav.java` adapts the obstacle tracking, wall-following side
selection, target reset, and loop-state logic from Ivan Geffner's
[BC26 BugNav implementation](https://github.com/IvanGeffner/BC26/blob/70b3f40cebefcca9dc858771715d78088e7ae520/basic51/BugNav.java).

This planner is fast and usually takes direct routes, but its map-edge side
switching has a static single-agent counterexample documented below.

### Classical Bug2 (`classicbug2`)

`src/classicbug2/ClassicBug2.java` is a grid adaptation of classical Bug2. Bug2
was selected instead of Bug1 because it needs less state and does not require a
complete boundary traversal followed by a second traversal to the best leave
point.

The implementation maintains:

- the start-to-goal m-line;
- an obstacle hit point and hit-point distance;
- a fixed boundary-following handedness for each encounter;
- explicit contact with the most recently observed blocked tile; and
- a leave condition requiring a closer m-line crossing.

Map edges are treated as ordinary obstacle boundaries. An unreachable target is
reported only after returning to the hit point following a complete boundary
traversal. Classical completeness assumptions still require static obstacles;
another moving robot can invalidate them.

### Real-Time Adaptive A* (`rtaa`)

`src/rtaa/RealTimeAdaptiveAStar.java` implements bounded Real-Time Adaptive A*
(RTAA*) over the eight-neighbor grid. Each movement turn it:

- records newly sensed passable and blocked terrain;
- runs a limited A* lookahead from the rat's current location;
- selects the lowest-`f` frontier state and takes the first step toward it; and
- updates every expanded state's heuristic to `f* - g(s)`.

The adaptive heuristic persists while pursuing the same target. Unknown tiles
are optimistically treated as passable, while sensed static walls remain in a
compact byte-array map. Search state uses preallocated primitive arrays and a
binary heap. Its hot path uses unrolled eight-neighbor expansion,
generation-stamped map arrays, and one packed `f`/`h`/state heap key. Direction
conversion uses the engine's one-cost `Direction.fromDelta`, and movement
avoids redundant legality checks.

The lookahead is capped at 14 node expansions per turn and also stops if fewer
than 4,000 bytecodes remain. Fourteen was selected by the deterministic
comb/spiral sweep below: it had the highest completion count, best aggregate
stretch, and lower bytecode usage than the saturated 16-expansion run. The
largest measured turn in that sweep used 16,893 of the 17,500-bytecode limit.
RTAA* checks movement legality immediately before turning and moving, and falls
back to the best legal adjacent tile when the planned step is temporarily
occupied.

## Test maps

| Map | Purpose |
| --- | --- |
| `BugNavTest` | Standard symmetric wall barriers with gaps. |
| `BugNavAdversarial` | A 32×32 maze with 182 walls (17.8%), long one-tile corridors, and capped dead ends. |
| `BugNavSingleAgentTrap` | Minimal static boundary counterexample: a reachable three-step target separated by two relevant wall tiles. |

All maps contain cheese mines beneath the stationary kings and no cats or dirt.
The counterexample map contains rotationally mirrored tests for the two required
teams; the babies remain far apart and do not interact.

### Forty-map benchmark suite

`GeneratePathfindingMapSuite` reproducibly creates `PathSuite01` through
`PathSuite40`, covering five wall families with eight variants each:

- staggered barriers with offset gates;
- interconnected rooms with varied door placement;
- nested rings with paired entrances;
- comb obstacles with long dead ends; and
- nested spiral/U-shaped obstacles.

The suite varies map dimensions from 24 to 38 tiles and wall density from about
5% to 16%. Every layout is rotationally symmetric and unique. The generator
also clears generous king/spawn zones, places a cheese mine beneath each king,
uses a deterministic cheese seed verified to sustain the stationary kings for
the full 2,000 rounds, uses no cats or dirt, connects every passable tile to the
kings, and calls the engine's map validator before writing the map. Map names
are listed in `maps/path-suite-maps.txt`.

Map sources are under `test/maptools/`, and generated `.map26` files are under
`maps/`.

## Replay-based performance analysis

`test/pathanalysis/PathPerformanceAnalyzer.java` reads the engine's gzip-wrapped
FlatBuffer replay. Per-round path collection therefore consumes no bot
bytecodes. A baby emits only one `PATH_TARGET` indicator when its target changes.
The RTAA* baby additionally redraws a magenta indicator dot on its current goal
each round so the destination remains visible during replay playback.

The analyzer writes generated CSVs under `analysis/`:

- `path-trace.csv`: round, location, facing, cooldowns, engine-measured
  bytecodes, active target, and segment ID.
- `path-summary.csv`: actual and optimal steps/rounds, detour, path efficiency,
  completion status, total bytecodes, mean/peak bytecodes per turn, and
  bytecodes per movement step.

The optimal baseline is an eight-neighbor BFS over static passability. Walls,
dirt, and initial robot footprints are blocked; dynamic robot collisions are
included in actual performance but intentionally excluded from the optimum.

## Current results

These are 2,000-round engine runs. Random target streams diverge after planners
finish or reject targets at different times, so the results are aggregate rather
than perfectly paired.

### Deterministic RTAA* expansion sweep

The `rtaabench` package assigns a new pseudo-random target on fixed 200-round
boundaries. Each target is a pure function of map dimensions, baby spawn, and
episode number, so planner progress cannot alter later assignments. Across all
nine expansion settings, all 2,560 cross-setting target comparisons matched.

The sweep covers the eight comb and eight spiral maps: 320 fixed target episodes
per setting, of which 283 are statically reachable. Stretch is total actual
steps divided by total optimal steps over completed reachable episodes.

| Expansions | Reached / reachable | Completion | Aggregate stretch | Bytecodes / turn | Peak bytecodes |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 237 / 283 | 83.7% | 2.170× | 6,948.6 | 11,621 |
| 6 | 241 / 283 | 85.2% | 2.100× | 8,049.3 | 13,621 |
| 7 | 244 / 283 | 86.2% | 2.000× | 9,085.6 | 15,657 |
| 8 | 245 / 283 | 86.6% | 2.080× | 10,101.3 | 16,891 |
| 9 | 254 / 283 | 89.8% | 1.990× | 10,870.7 | 16,893 |
| 10 | 256 / 283 | 90.5% | 1.960× | 11,674.0 | 16,893 |
| 12 | 253 / 283 | 89.4% | 1.880× | 12,647.2 | 16,893 |
| **14** | **262 / 283** | **92.6%** | **1.800×** | **12,970.3** | **16,893** |
| 16 | 262 / 283 | 92.6% | 1.840× | 13,059.4 | 16,979 |

At the same cap of five on `PathSuite04_Combs`, bytecode optimization preserved
the exact 10 completed routes (454 actual versus 176 optimal steps) while
reducing mean usage from 9,114.7 to 7,092.5 bytecodes per turn (22.2%) and the
peak from 14,516 to 11,468 (21.0%).

### Forty-map suite

This earlier cross-planner suite used the pre-optimization five-expansion RTAA*
configuration; it is retained as the like-for-like three-planner baseline.

The full suite comprises 120 matches: each of the three planners ran for 2,000
rounds on all 40 maps. A target assignment is *reachable* when the analyzer's
static eight-neighbor BFS finds a path. Random targets that land on walls are
excluded. `Reached / reachable` is the raw completion count; `adjusted` also
excludes reachable targets assigned too near round 2,000 to finish.

| Planner | Reached / reachable | Raw completion | Match-ended | Retargeted | Adjusted completion |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ivan-derived BugNav | 5,481 / 5,576 | 98.30% | 69 | 26 | 99.53% |
| Classical Bug2 | 3,582 / 3,878 | 92.37% | 74 | 222 | 94.16% |
| RTAA* (5 expansions) | 3,755 / 3,843 | 97.71% | 72 | 16 | 99.58% |

`Retargeted` means the bot changed away from a statically reachable target
before arriving. Dynamic occupancy can cause this for every bot; Bug2 may also
retarget after its boundary traversal declares the target unreachable. The
replay does not distinguish those causes, so this column should not be read as
a pure single-agent incompleteness count.

Path-quality metrics below include only completed reachable assignments.
Aggregate stretch is total actual steps divided by total optimal steps; lower is
better, and `1.000×` is optimal. Mean efficiency is the per-assignment mean of
`optimal / actual`, so higher is better.

| Planner | Actual / optimal steps | Aggregate stretch | Mean efficiency | Exactly optimal | Bytecodes / turn | Peak bytecodes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Ivan-derived BugNav | 134,049 / 93,196 | 1.438× | 0.856 | 51.7% | 498.5 | 16,119 |
| Classical Bug2 | 113,180 / 59,009 | 1.918× | 0.742 | 35.1% | 352.7 | 671 |
| RTAA* (5 expansions) | 110,818 / 61,901 | 1.790× | 0.869 | 55.7% | 9,090.6 | 14,854 |

Bytecodes per turn are aggregated over completed reachable assignments. Peak
bytecodes include all 159,920 observed baby-rat turns per planner; none of the
three planners reached the 17,500-bytecode limit anywhere in the suite.

BugNav has the best aggregate path length and near-perfect adjusted completion.
RTAA* reaches the highest fraction of exact-optimal paths and has the best mean
efficiency, but a small number of very long comb/spiral routes raise its
aggregate stretch above BugNav. Bug2 uses the fewest bytecodes but has both the
lowest completion rate and longest routes.

Rooms and rings are the easiest structures: BugNav uses `1.09×` and `1.18×`
optimal steps, while RTAA* uses `1.07×` and `1.35×`. Combs and spirals are the
hardest. Their aggregate stretches are `2.12×`/`1.69×` for BugNav,
`2.88×`/`2.51×` for Bug2, and `2.89×`/`3.63×` for RTAA*, respectively.

### Standard map

| Planner | Reached / segments | Mean path efficiency | Bytecodes / turn | Bytecodes / step |
| --- | ---: | ---: | ---: | ---: |
| Ivan-derived BugNav | 246 / 280 | 0.915 | 342.7 | 368.0 |
| Classical Bug2 | 173 / 207 | 0.852 | 313.3 | 331.1 |
| RTAA* (5 expansions) | 192 / 221 | 0.959 | 8,515.8 | 9,192.5 |

### One-wide-corridor adversarial map

| Planner | Reached / segments | Mean path efficiency | Bytecodes / turn | Bytecodes / step |
| --- | ---: | ---: | ---: | ---: |
| Ivan-derived BugNav | 75 / 97 | 0.807 | 597.0 | 615.0 |
| Classical Bug2 | 48 / 73 | 0.640 | 395.9 | 403.7 |
| RTAA* (5 expansions) | 70 / 91 | 0.751 | 8,233.3 | 8,433.4 |

Bug2 is more conservative and produces longer routes, but it uses fewer
bytecodes—especially while following complex boundaries. RTAA* produces the
most efficient routes on the standard map and remains competitive in the
one-wide maze, at the expected cost of substantially more planning bytecodes.
Its measured peak was 14,838 bytecodes on the standard map and 14,532 on the
adversarial map.

### Static single-agent counterexample

For team A, the baby starts at `(0,5)`, targets `(0,2)`, and has walls at
`(0,3)` and `(0,4)`. The optimal route is:

```text
(0,5) -> (1,4) -> (1,3) -> (0,2)
```

| Planner | Result | Steps | Rounds | Mean bytecodes / observed turn |
| --- | --- | ---: | ---: | ---: |
| Ivan-derived BugNav | Stalls at `(1,4)` | 1 | 1,998 without arrival | 1,129.4 |
| Classical Bug2 | Reaches target | 3 | 3 | 313.4 |

The Ivan-derived module repeatedly flips its wall-following side when candidate
directions extend beyond the map boundary. Classical Bug2 preserves its
boundary-following orientation and completes the optimal route.

## Commands

On Windows, replace `./gradlew` with `.\gradlew.bat`.

```text
# Install/update dependencies and compile
./gradlew update build

# Run the default Ivan-derived bot on BugNavTest
./gradlew run

# Regenerate all custom maps
./gradlew generatePathfindingTestMap
./gradlew generateAdversarialPathfindingMap
./gradlew generateSingleAgentBugNavTrapMap
./gradlew generatePathfindingMapSuite

# Run and analyze Ivan-derived BugNav
./gradlew benchmarkPaths -PteamA=pathplanner -PteamB=pathplanner -Pmaps=BugNavAdversarial -PanalysisOutput=analysis/ivan/adversarial

# Run and analyze classical Bug2
./gradlew benchmarkPaths -PteamA=classicbug2 -PteamB=classicbug2 -Pmaps=BugNavAdversarial -PanalysisOutput=analysis/classic-bug2/adversarial

# Run and analyze Real-Time Adaptive A*
./gradlew benchmarkPaths -PteamA=rtaa -PteamB=rtaa -Pmaps=BugNavAdversarial -PanalysisOutput=analysis/rtaa/adversarial

# Reproduce the fixed-target comparison
./gradlew benchmarkPaths -PteamA=bugnavcounterexample -PteamB=bugnavcounterexample -Pmaps=BugNavSingleAgentTrap -PanalysisOutput=analysis/single-agent-trap
./gradlew benchmarkPaths -PteamA=classicbug2counterexample -PteamB=classicbug2counterexample -Pmaps=BugNavSingleAgentTrap -PanalysisOutput=analysis/classic-bug2/single-agent-trap

# Run all three planners on all 40 suite maps (120 analyzed matches)
.\tools\benchmark_path_suite.ps1

# Run a smaller slice, useful for a smoke test
.\tools\benchmark_path_suite.ps1 -Start 1 -Limit 2

# Sweep deterministic RTAA* targets on every comb and spiral map
.\tools\benchmark_rtaa_expansions.ps1
```

To analyze an existing replay without rerunning a match, use `analyzePaths` with
`-PanalysisReplay=<file>`, `-PanalysisMap=<name>`, and optionally
`-PanalysisOutput=<directory>`.

## Repository layout

```text
src/pathplanner/                 Ivan-derived BugNav bot
src/classicbug2/                 Classical Bug2 bot
src/rtaa/                        Real-Time Adaptive A* bot
src/rtaabench/                   Fixed-schedule RTAA* benchmark bot
src/*counterexample/             Fixed-target comparison harnesses
maps/                            Generated Battlecode maps
test/maptools/                   Reproducible map generators
test/pathanalysis/               Replay and optimal-path analyzer
tools/benchmark_path_suite.ps1   Three-planner suite runner
tools/benchmark_rtaa_expansions.ps1
                                 Deterministic RTAA* expansion sweep
tools/search_bugnav_counterexample.mjs
                                 Static counterexample search harness
```

Generated clients, replays, builds, dependency caches, JVM socket scratch data,
and analysis CSVs are intentionally excluded by `.gitignore`.
