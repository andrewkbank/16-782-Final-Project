import fs from 'node:fs';
import path from 'node:path';

// Usage: node tools/summarize_path_benchmarks.mjs analysis/suite/lrta reports/lrta
// Inputs are the analyzer's numeric/unquoted CSVs, not arbitrary CSV documents.
const [input = 'analysis/suite/lrta', output = 'reports/lrta'] = process.argv.slice(2);
function readCsv(file) {
  const [header, ...lines] = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
  const names = header.split(',');
  return lines.map(line => Object.fromEntries(line.split(',').map((v, i) => [names[i], v])));
}
const sum = (rows, key) => rows.reduce((n, row) => n + Number(row[key]), 0);
const divide = (n, d) => d ? (n / d).toFixed(6) : '';
const peak = (rows, key) => rows.reduce((n, row) => Math.max(n, Number(row[key])), 0);
function summarize(name, segments, turns) {
  const reachable = segments.filter(s => s.optimal_steps !== '');
  const completed = reachable.filter(s => s.status === 'reached');
  const hasTelemetry = turns.some(t => t.expanded_states !== undefined && t.expanded_states !== '');
  const planned = turns.filter(t => t.expanded_states !== '' && Number(t.expanded_states) > 0);
  return {
    map: name,
    assignments: segments.length,
    reachable: reachable.length,
    reached: completed.length,
    completion: divide(completed.length, reachable.length),
    match_ended: reachable.filter(s => s.status === 'match-ended').length,
    reachable_stalled: reachable.filter(s => s.status === 'reachable-stalled').length,
    retargeted: reachable.filter(s => s.status === 'retargeted').length,
    actual_steps: sum(completed, 'actual_steps'),
    optimal_steps: sum(completed, 'optimal_steps'),
    stretch: divide(sum(completed, 'actual_steps'), sum(completed, 'optimal_steps')),
    mean_efficiency: divide(sum(completed, 'path_efficiency'), completed.length),
    exact_optimal: completed.filter(s => Number(s.actual_steps) === Number(s.optimal_steps)).length,
    completed_bytecodes_per_turn: divide(sum(completed, 'total_bytecodes'), sum(completed, 'observed_turns')),
    all_turns: turns.length,
    all_bytecodes_per_turn: divide(sum(turns, 'bytecodes_used'), turns.length),
    peak_bytecodes: peak(turns, 'bytecodes_used'),
    turns_at_limit: turns.filter(t => Number(t.bytecodes_used) >= 17500).length,
    planning_turns: hasTelemetry ? planned.length : '',
    mean_expansions: divide(sum(planned, 'expanded_states'), planned.length),
    max_expansions: hasTelemetry ? peak(planned, 'expanded_states') : '',
    expanded_states: hasTelemetry ? sum(planned, 'expanded_states') : '',
    planning_bytecodes_per_expansion: divide(sum(planned, 'bytecodes_used'), sum(planned, 'expanded_states')),
  };
}
const maps = fs.readdirSync(input).filter(name => fs.existsSync(path.join(input, name, 'path-summary.csv'))).sort();
if (!maps.length) throw new Error(`No analyzed maps in ${input}`);
const data = maps.map(name => ({name,
  segments: readCsv(path.join(input, name, 'path-summary.csv')),
  turns: readCsv(path.join(input, name, 'path-trace.csv')),
}));
const rows = data.map(d => summarize(d.name, d.segments, d.turns));
for (const [name, subset] of [
  ['TOTAL', data], ['SUITE40', data.filter(d => d.name.startsWith('PathSuite'))],
  ...['Staggered', 'Rooms', 'Rings', 'Combs', 'Spirals'].map(f => [f, data.filter(d => d.name.endsWith('_' + f))]),
]) {
  if (subset.length) rows.push(summarize(name, subset.flatMap(d => d.segments), subset.flatMap(d => d.turns)));
}
fs.mkdirSync(output, {recursive: true});
const keys = Object.keys(rows[0]);
fs.writeFileSync(path.join(output, 'summary.csv'), keys.join(',') + '\n' + rows.map(r => keys.map(k => r[k]).join(',')).join('\n') + '\n');
console.log(JSON.stringify(rows.filter(r => ['TOTAL', 'SUITE40'].includes(r.map)), null, 2));
