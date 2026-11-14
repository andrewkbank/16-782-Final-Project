import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

// Engine-analyzer CSVs contain numeric values and unquoted identifiers only.
const [input = 'analysis/concavity', output = 'reports/concavity'] = process.argv.slice(2);
const scenarios = JSON.parse(fs.readFileSync('maps/concave-scenarios.json', 'utf8'));
const planners = [
  ['RTAA', 'concavertaabench', 'rtaabench'], ['LRTA', 'concavelrtabench', 'lrtabench'],
  ['BugNav', 'concavebugnavbench', 'bugnavbench'], ['Bug2', 'concavebug2bench', 'bug2bench'],
];
function csv(file) {
  const [header, ...lines] = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
  const keys = header.split(',');
  return lines.map(line => Object.fromEntries(line.split(',').map((v, i) => [keys[i], v])));
}
function write(name, rows) {
  const keys = Object.keys(rows[0]);
  fs.writeFileSync(path.join(output, name), keys.join(',') + '\n' +
    rows.map(row => keys.map(k => row[k] ?? '').join(',')).join('\n') + '\n');
}
const sum = (rows, key) => rows.reduce((n, r) => n + Number(r[key]), 0);
const ratio = (a, b) => b ? +(a / b).toFixed(6) : '';
function load(bot, map) {
  const dir = path.join(input, bot, map);
  const archived = path.join(output, 'segments', bot);
  fs.mkdirSync(archived, {recursive: true});
  fs.copyFileSync(path.join(dir, 'path-summary.csv'), path.join(archived, map + '.csv'));
  return {segments: csv(path.join(dir, 'path-summary.csv')), turns: csv(path.join(dir, 'path-trace.csv'))};
}
function stats(segments, turns) {
  const reachable = segments.filter(s => s.optimal_steps !== '');
  const reached = reachable.filter(s => s.status === 'reached');
  const planned = turns.filter(t => +t.expanded_states > 0);
  return {
    assignments: segments.length, reachable: reachable.length, reached: reached.length,
    completion: ratio(reached.length, reachable.length),
    completed_steps: sum(reached, 'actual_steps'), completed_bfs: sum(reached, 'optimal_steps'),
    completed_stretch: ratio(sum(reached, 'actual_steps'), sum(reached, 'optimal_steps')),
    active_mean_bytecodes: ratio(sum(segments, 'total_bytecodes'), sum(segments, 'observed_turns')),
    peak_bytecodes: Math.max(...turns.map(t => +t.bytecodes_used)),
    turns_at_limit: turns.filter(t => +t.bytecodes_used >= 17500).length,
    observed_turns: turns.length,
    measured_planning_turns: planned.length || '',
    mean_expansions: ratio(sum(planned, 'expanded_states'), planned.length),
    max_expansions: planned.length ? Math.max(...planned.map(t => +t.expanded_states)) : '',
  };
}
fs.mkdirSync(output, {recursive: true});
const escape = [], exploration = [], aggregates = [], datasets = [];
for (const scenario of scenarios) {
  const {map, family} = scenario;
  const schedules = [];
  for (const [planner, freshBot, scheduleBot] of planners) {
    const fresh = load(freshBot, map);
    assert.equal(fresh.segments.length, 1, `${map}: expected one fresh target`);
    assert.equal(new Set(fresh.turns.map(t => t.robot_id)).size, 1);
    assert.equal(fresh.turns.length, 1999, `${map}: incomplete fresh trial`);
    assert.equal(+fresh.turns.at(-1).round, 2000);
    assert.ok(fresh.turns.every(t => +t.bytecodes_used < 17500), `${map}: fresh trial hit bytecode limit`);
    const s = fresh.segments[0];
    for (const key of ['start_x', 'start_y', 'target_x', 'target_y']) assert.equal(+s[key], scenario[key]);
    assert.equal(+s.optimal_steps, scenario.bfs_steps, `${map}: BFS disagreement`);
    const active = fresh.turns.filter(t => +t.round >= +s.assignment_round && +t.round <= +s.finish_round);
    const visited = new Set([`${s.start_x}:${s.start_y}`]);
    let previous = `${s.start_x}:${s.start_y}`, moves = 0, revisits = 0, escapeRound = '', escapeSteps = '';
    for (const turn of active) {
      const cell = `${turn.x}:${turn.y}`;
      if (cell !== previous) {
        moves++;
        if (visited.has(cell)) revisits++;
        visited.add(cell);
      }
      previous = cell;
      if (scenario.escape_y !== null && escapeRound === '' && +turn.y > scenario.escape_y) {
        escapeRound = +turn.round - +s.assignment_round;
        escapeSteps = moves;
      }
    }
    assert.equal(moves, +s.actual_steps, `${map}: trace/summary movement disagreement`);
    const right = scenario.size / 2 - 4, middle = (4 + right) / 2;
    const adjacentBayTurns = family === 'CombBays' && escapeRound !== ''
      ? active.filter(t => +t.round > +s.assignment_round + escapeRound &&
          +t.x > middle && +t.x < right && +t.y > 4 && +t.y <= scenario.escape_y).length : '';
    escape.push({map, family, planner, trap_free_cells: scenario.trap_free_cells,
      bfs_steps: scenario.bfs_steps, status: s.status, steps: moves,
      rounds: +s.actual_rounds, stretch: s.status === 'reached' ? ratio(moves, scenario.bfs_steps) : '',
      escape_rounds: escapeRound, escape_steps: escapeSteps, unique_cells: visited.size,
      revisits, revisit_fraction: ratio(revisits, moves), adjacent_bay_turns_after_escape: adjacentBayTurns,
      ...stats(fresh.segments, fresh.turns)});
    datasets.push({mode: 'escape', planner, family, ...fresh});

    const scheduled = load(scheduleBot, map);
    assert.equal(scheduled.segments.length, 20, `${map}: expected 20 scheduled episodes`);
    assert.equal(scheduled.turns.length, 3998, `${map}: incomplete exploration trial`);
    assert.ok(scheduled.turns.every(t => +t.bytecodes_used < 17500), `${map}: exploration hit bytecode limit`);
    schedules.push(scheduled.segments.map(s => [s.robot_id, s.assignment_round, s.target_x, s.target_y].join(':')).sort());
    exploration.push({map, family, planner, ...stats(scheduled.segments, scheduled.turns)});
    datasets.push({mode: 'exploration', planner, family, ...scheduled});
  }
  for (let i = 1; i < schedules.length; i++) {
    assert.deepEqual(schedules[0], schedules[i], `${map}: ${planners[i][0]} target schedule differs`);
  }
}
for (const mode of ['escape', 'exploration']) for (const [planner] of planners) {
  for (const group of ['concave', 'open']) {
    const data = datasets.filter(d => d.mode === mode && d.planner === planner && (d.family === 'Open') === (group === 'open'));
    aggregates.push({mode, planner, group, ...stats(data.flatMap(d => d.segments), data.flatMap(d => d.turns))});
  }
}
write('escape-comparison.csv', escape);
write('escape-all-planners.csv', scenarios.map(({map, family, bfs_steps}) => {
  const row = {map, family, bfs_steps};
  for (const [planner] of planners) {
    const result = escape.find(e => e.map === map && e.planner === planner);
    for (const key of ['status', 'steps', 'stretch', 'escape_rounds', 'revisits']) {
      row[`${planner.toLowerCase()}_${key}`] = result[key];
    }
  }
  return row;
}));
write('escape-pairs.csv', scenarios.map(({map, family, bfs_steps}) => {
  const r = escape.find(e => e.map === map && e.planner === 'RTAA');
  const l = escape.find(e => e.map === map && e.planner === 'LRTA');
  const bothReached = r.status === 'reached' && l.status === 'reached';
  return {map, family, bfs_steps, rtaa_status: r.status, lrta_status: l.status,
    rtaa_steps: r.steps, lrta_steps: l.steps,
    lrta_step_change_percent: bothReached ? ratio(100 * (l.steps - r.steps), r.steps) : '',
    rtaa_stretch: r.stretch, lrta_stretch: l.stretch,
    rtaa_escape_rounds: r.escape_rounds, lrta_escape_rounds: l.escape_rounds,
    rtaa_revisits: r.revisits, lrta_revisits: l.revisits};
}));
write('exploration-comparison.csv', exploration);
write('aggregate-comparison.csv', aggregates);
const sources = ['src/rtaa/RealTimeAdaptiveAStar.java', 'src/lrta/LearningRealTimeAStar.java',
  'src/pathplanner/BugNav.java', 'src/classicbug2/ClassicBug2.java',
  'src/concavebugnavbench/RobotPlayer.java', 'src/concavebug2bench/RobotPlayer.java',
  'src/bugnavbench/RobotPlayer.java', 'src/bug2bench/RobotPlayer.java',
  'src/concavertaabench/RobotPlayer.java', 'src/concavelrtabench/RobotPlayer.java',
  'src/rtaabench/RobotPlayer.java', 'src/lrtabench/RobotPlayer.java',
  'test/maptools/GenerateConcaveMapSuite.java', 'test/pathanalysis/PathPerformanceAnalyzer.java',
  ...scenarios.map(s => `maps/${s.map}.map26`)];
fs.writeFileSync(path.join(output, 'provenance.json'), JSON.stringify({
  analyzed_at: new Date().toISOString(), input, rounds: 2000, seed: 6147,
  sha256: Object.fromEntries(sources.map(file => [file, createHash('sha256').update(fs.readFileSync(file)).digest('hex')])),
}, null, 2) + '\n');
console.log(JSON.stringify({escape, aggregates}, null, 2));
