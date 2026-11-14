import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

const [input = 'analysis/traffic', output = 'reports/traffic'] = process.argv.slice(2);
const maps = JSON.parse(fs.readFileSync('maps/traffic-scenarios.json', 'utf8'));
const planners = ['bugnav', 'rtaa', 'lrta'];
fs.mkdirSync(output, {recursive: true});
function csv(file) {
  const [header, ...lines] = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
  const keys = header.split(',');
  return lines.map(line => Object.fromEntries(line.split(',').map((v, i) => [keys[i], v])));
}
function write(name, rows) {
  const keys = Object.keys(rows[0]);
  fs.writeFileSync(path.join(output, name), keys.join(',') + '\n' + rows.map(r => keys.map(k => r[k] ?? '').join(',')).join('\n') + '\n');
}
const sum = (rows, k) => rows.reduce((a, r) => a + +r[k], 0);
const div = (a, b) => b ? +(a / b).toFixed(6) : '';
const cell = (x, y, m) => +y * m.width + +x;
const key = (a) => `${a.start_x}:${a.start_y}:${a.target_x}:${a.target_y}`;
function search(m, start, goal, occupied = new Set()) {
  const parents = new Int32Array(m.width * m.height).fill(-1), q = [start];
  parents[start] = start;
  for (let cursor = 0; cursor < q.length; cursor++) {
    const c = q[cursor];
    if (c === goal) {
      const route = [c];
      while (route.at(-1) !== start) route.push(parents[route.at(-1)]);
      return route.reverse();
    }
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      const x = c % m.width + dx, y = Math.floor(c / m.width) + dy, n = cell(x, y, m);
      if (x < 0 || x >= m.width || y < 0 || y >= m.height || m.blocked_rows[y][x] === '#' || occupied.has(n) || parents[n] !== -1) continue;
      parents[n] = c; q.push(n);
    }
  }
  return null;
}
// Construct a feasible schedule, not a joint optimum: one robot moves at a time,
// with all other waiting/parked robots treated as occupied cells throughout.
function serialSchedule(m, scenario) {
  const positions = scenario.agents.map(a => cell(a.start_x, a.start_y, m));
  const pending = new Set(positions.map((_, i) => i)), certificate = [];
  let steps = 0;
  while (pending.size) {
    let selected = false;
    for (const i of pending) {
      const occupied = new Set(positions.filter((_, j) => i !== j));
      const a = scenario.agents[i], goal = cell(a.target_x, a.target_y, m);
      const route = search(m, positions[i], goal, occupied);
      if (!route) continue;
      for (let t = 1; t < route.length; t++) {
        const before = route[t - 1], next = route[t];
        assert.ok(!occupied.has(next));
        assert.ok(Math.abs(next % m.width - before % m.width) <= 1);
        assert.ok(Math.abs(Math.floor(next / m.width) - Math.floor(before / m.width)) <= 1);
        assert.notEqual(next, before);
        assert.notEqual(m.blocked_rows[Math.floor(next / m.width)][next % m.width], '#');
      }
      certificate.push({side: a.side, rank: a.rank, start_round: m.release + steps + 1,
        route: route.map(c => [c % m.width, Math.floor(c / m.width)])});
      steps += route.length - 1;
      positions[i] = goal; pending.delete(i); selected = true; break;
    }
    assert.ok(selected, `${m.map}/${scenario.mode}: no feasible serial crossing order found`);
  }
  assert.ok(steps <= m.rounds - m.release, 'Feasible schedule exceeds experiment horizon');
  return {map: m.map, mode: scenario.mode, steps, certificate};
}
const feasible = maps.flatMap(m => m.cases.map(s => serialSchedule(m, s)));
fs.writeFileSync(path.join(output, 'feasible-schedules.json'), JSON.stringify(feasible, null, 2) + '\n');
if (process.argv.includes('--feasibility-only')) {
  console.log(JSON.stringify(feasible.map(({map, mode, steps}) => ({map, mode, steps})), null, 2));
  process.exit(0);
}
const agents = [], trials = [], signatures = new Map();
for (const m of maps) for (const scenario of m.cases) {
  const schedule = feasible.find(s => s.map === m.map && s.mode === scenario.mode);
  for (const planner of planners) {
    const bot = `traffic${planner}${scenario.mode}`, dir = path.join(input, bot, m.map);
    const summary = csv(path.join(dir, 'path-summary.csv'));
    const trace = csv(path.join(dir, 'path-trace.csv')).filter(t => +t.round >= m.release && +t.round <= m.rounds);
    assert.equal(summary.length, scenario.agents.length, `${bot}/${m.map}: missing agent`);
    const signature = summary.map(s => [s.robot_id, s.assignment_round, key(s)].join(':')).sort();
    const signatureKey = `${m.map}/${scenario.mode}`;
    if (signatures.has(signatureKey)) assert.deepEqual(signature, signatures.get(signatureKey), 'Planner schedules differ');
    else signatures.set(signatureKey, signature);
    const snapshots = new Map();
    for (const t of trace) {
      if (!snapshots.has(+t.round)) snapshots.set(+t.round, []);
      snapshots.get(+t.round).push(t);
    }
    for (let r = m.release; r <= m.rounds; r++) {
      const snapshot = snapshots.get(r);
      assert.equal(snapshot?.length, scenario.agents.length, `${bot}/${m.map}: incomplete round ${r}`);
      assert.equal(new Set(snapshot.map(t => `${t.x}:${t.y}`)).size, snapshot.length, 'Overlapping agents');
      for (const t of snapshot) assert.notEqual(m.blocked_rows[+t.y][+t.x], '#', 'Agent in static obstacle');
    }
    const perTrial = [];
    for (const s of summary) {
      const expected = scenario.agents.find(a => key(a) === key(s));
      assert.ok(expected, `${bot}/${m.map}: unexpected start or target`);
      assert.equal(+s.assignment_round, m.release);
      assert.equal(+s.optimal_steps, expected.bfs_steps);
      const start = cell(s.start_x, s.start_y, m), goal = cell(s.target_x, s.target_y, m);
      assert.equal(search(m, start, goal).length - 1, expected.bfs_steps, 'Independent BFS disagreement');
      const turns = trace.filter(t => t.robot_id === s.robot_id).sort((a, b) => +a.round - +b.round);
      assert.equal(turns.length, m.rounds - m.release + 1);
      const finish = turns.find(t => +t.x === +s.target_x && +t.y === +s.target_y);
      const endRound = finish ? +finish.round : m.rounds;
      const active = turns.filter(t => +t.round <= endRound);
      const seen = new Set([start]), distanceCache = new Map();
      let previous = start, moves = 0, revisits = 0, stationary = 0, longestStationary = 0;
      let neighborWaits = 0, lastImprovement = m.release, bestDistance = expected.bfs_steps, corridorExit = '';
      for (const t of active) {
        const c = cell(t.x, t.y, m);
        if (+t.round > m.release) {
          if (c !== previous) {
            assert.ok(Math.abs(c % m.width - previous % m.width) <= 1 && Math.abs(Math.floor(c / m.width) - Math.floor(previous / m.width)) <= 1);
            moves++; if (seen.has(c)) revisits++; seen.add(c); stationary = 0;
          } else {
            stationary++; longestStationary = Math.max(longestStationary, stationary);
            if (+t.x >= 20 && +t.x <= 27 && snapshots.get(+t.round).some(o =>
              o.robot_id !== t.robot_id && Math.max(Math.abs(+o.x - +t.x), Math.abs(+o.y - +t.y)) <= 1)) neighborWaits++;
          }
        }
        if (!distanceCache.has(c)) distanceCache.set(c, search(m, c, goal).length - 1);
        const d = distanceCache.get(c);
        if (d < bestDistance) { bestDistance = d; lastImprovement = +t.round; }
        if (corridorExit === '' && (expected.side === 'A' ? +t.x > 27 : +t.x < 20)) corridorExit = +t.round - m.release;
        previous = c;
      }
      const elapsed = endRound - m.release;
      const result = {map: m.map, mode: scenario.mode, planner, robot_id: +s.robot_id,
        side: expected.side, rank: expected.rank, bfs_steps: expected.bfs_steps,
        status: finish ? 'reached' : 'unfinished', travel_rounds_capped: elapsed, steps: moves,
        stretch: finish ? div(moves, expected.bfs_steps) : '', wait_rounds: elapsed - moves,
        revisits, longest_stationary_run: longestStationary, terminal_stationary_rounds: finish ? 0 : stationary,
        unfinished_no_improvement_100: !finish && endRound - lastImprovement >= 100 ? 1 : 0,
        stationary_near_robot_in_corridor: neighborWaits, corridor_exit_rounds: corridorExit,
        active_bytecodes: sum(active, 'bytecodes_used'), active_turns: active.length,
        peak_bytecodes: Math.max(...turns.map(t => +t.bytecodes_used)),
        turns_at_limit: turns.filter(t => +t.bytecodes_used >= 17500).length};
      assert.equal(result.turns_at_limit, 0, `${bot}/${m.map}: bytecode limit`);
      agents.push(result); perTrial.push(result);
    }
    const reached = perTrial.filter(r => r.status === 'reached');
    trials.push({map: m.map, mode: scenario.mode, planner, agents: perTrial.length, reached: reached.length,
      completion: div(reached.length, perTrial.length),
      completed_stretch: div(sum(reached, 'steps'), sum(reached, 'bfs_steps')),
      mean_capped_travel_rounds: div(sum(perTrial, 'travel_rounds_capped'), perTrial.length),
      makespan_if_all_reached: reached.length === perTrial.length ? Math.max(...reached.map(r => r.travel_rounds_capped)) : '',
      serial_feasible_rounds: schedule.steps,
      steps: sum(perTrial, 'steps'), wait_rounds: sum(perTrial, 'wait_rounds'), revisits: sum(perTrial, 'revisits'),
      unfinished_no_improvement_100: sum(perTrial, 'unfinished_no_improvement_100'),
      stationary_near_robot_in_corridor: sum(perTrial, 'stationary_near_robot_in_corridor'),
      mean_active_bytecodes: div(sum(perTrial, 'active_bytecodes'), sum(perTrial, 'active_turns')),
      peak_bytecodes: Math.max(...perTrial.map(r => r.peak_bytecodes)), turns_at_limit: sum(perTrial, 'turns_at_limit'),
      observed_turns: trace.length});
    const archive = path.join(output, 'segments', bot); fs.mkdirSync(archive, {recursive: true});
    fs.copyFileSync(path.join(dir, 'path-summary.csv'), path.join(archive, m.map + '.csv'));
  }
}
for (const r of trials) {
  const side = r.mode === 'solob' ? 'B' : 'A';
  const representative = agents.find(a => a.map === r.map && a.mode === r.mode && a.planner === r.planner && a.side === side && a.rank === 0);
  const solo = agents.find(a => a.map === r.map && a.mode === (side === 'A' ? 'solo' : 'solob') && a.planner === r.planner);
  r.rank0_status = representative.status;
  r.rank0_capped_slowdown_vs_solo = solo.status === 'reached' ? div(representative.travel_rounds_capped, solo.travel_rounds_capped) : '';
}
const aggregate = [];
for (const mode of maps[0].cases.map(c => c.mode)) for (const planner of planners) {
  const rows = agents.filter(a => a.mode === mode && a.planner === planner), done = rows.filter(a => a.status === 'reached');
  aggregate.push({mode, planner, agents: rows.length, reached: done.length, completion: div(done.length, rows.length),
    completed_stretch: div(sum(done, 'steps'), sum(done, 'bfs_steps')),
    mean_capped_travel_rounds: div(sum(rows, 'travel_rounds_capped'), rows.length),
    unfinished_no_improvement_100: sum(rows, 'unfinished_no_improvement_100'),
    wait_rounds: sum(rows, 'wait_rounds'), revisits: sum(rows, 'revisits'),
    mean_active_bytecodes: div(sum(rows, 'active_bytecodes'), sum(rows, 'active_turns')),
    peak_bytecodes: Math.max(...rows.map(r => r.peak_bytecodes)), turns_at_limit: sum(rows, 'turns_at_limit')});
}
const matchedControls = agents.filter(a => a.rank === 0 && !['solo', 'solob'].includes(a.mode)).map(a => {
  const solo = agents.find(s => s.map === a.map && s.planner === a.planner && s.mode === (a.side === 'A' ? 'solo' : 'solob'));
  return {map: a.map, mode: a.mode, planner: a.planner, side: a.side,
    solo_status: solo.status, traffic_status: a.status, solo_rounds: solo.travel_rounds_capped,
    traffic_rounds_capped: a.travel_rounds_capped,
    capped_slowdown: solo.status === 'reached' ? div(a.travel_rounds_capped, solo.travel_rounds_capped) : '',
    solo_steps: solo.steps, traffic_steps: a.steps, traffic_wait_rounds: a.wait_rounds};
});
write('agents.csv', agents); write('trials.csv', trials); write('aggregate.csv', aggregate);
write('matched-controls.csv', matchedControls);
fs.writeFileSync(path.join(output, 'feasible-schedules.json'), JSON.stringify(feasible, null, 2) + '\n');
const files = ['src/trafficbench/TrafficTrial.java', 'src/pathplanner/BugNav.java', 'src/rtaa/RealTimeAdaptiveAStar.java',
  'src/lrta/LearningRealTimeAStar.java', 'test/maptools/GenerateTrafficMapSuite.java', 'tools/analyze_traffic.mjs',
  'tools/benchmark_traffic.ps1', 'build.gradle',
  ...planners.flatMap(p => maps[0].cases.map(c => `src/traffic${p}${c.mode}/RobotPlayer.java`)),
  'test/pathanalysis/PathPerformanceAnalyzer.java', 'maps/traffic-scenarios.json', ...maps.map(m => `maps/${m.map}.map26`)];
fs.writeFileSync(path.join(output, 'provenance.json'), JSON.stringify({analyzed_at: new Date().toISOString(),
  release_round: 25, cutoff_round: 600, engine_termination_round: 601,
  sha256: Object.fromEntries(files.map(f => [f, createHash('sha256').update(fs.readFileSync(f)).digest('hex')])),
}, null, 2) + '\n');
console.log(JSON.stringify({aggregate, trials}, null, 2));
