/** Search static grids for reachable targets that this project's BugNav misses. */

const DIRECTIONS = [
  [0, 1], [1, 1], [1, 0], [1, -1],
  [0, -1], [-1, -1], [-1, 0], [-1, 1],
];
const INF = 1_000_000;
const key = ([x, y]) => `${x},${y}`;
const equal = (a, b) => a[0] === b[0] && a[1] === b[1];
const distanceSquared = (a, b) => (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2;

function directionTo(source, target) {
  const dx = target[0] - source[0];
  const dy = target[1] - source[1];
  if (Math.abs(dx) >= 2.414 * Math.abs(dy)) return dx > 0 ? 2 : dx < 0 ? 6 : null;
  if (Math.abs(dy) >= 2.414 * Math.abs(dx)) return dy > 0 ? 0 : 4;
  if (dy > 0) return dx > 0 ? 1 : 7;
  return dx > 0 ? 3 : 5;
}

class Navigator {
  constructor(width, height, walls, start, target) {
    this.width = width;
    this.height = height;
    this.walls = walls;
    this.location = [...start];
    this.target = target;
    this.rotateRight = true;
    this.obstacle = null;
    this.minDistance = INF;
    this.turnsToObstacle = 0;
    this.states = new Set();
  }

  onMap([x, y]) {
    return x >= 0 && y >= 0 && x < this.width && y < this.height;
  }

  destination(direction) {
    const [dx, dy] = DIRECTIONS[direction];
    return [this.location[0] + dx, this.location[1] + dy];
  }

  canStep(direction) {
    if (direction === null) return false;
    const destination = this.destination(direction);
    return this.onMap(destination) && !this.walls.has(key(destination));
  }

  reset(resetRotation = false) {
    this.obstacle = null;
    this.minDistance = INF;
    this.turnsToObstacle = 0;
    this.states.clear();
    if (resetRotation) this.rotateRight = null;
  }

  move(direction) {
    this.location = this.destination(direction);
  }

  chooseSide() {
    if (this.rotateRight !== null) return;
    const direct = directionTo(this.location, this.target);
    let left = direct;
    let right = direct;
    let leftLocation = this.location;
    let rightLocation = this.location;
    for (let i = 0; i < 8; i++) {
      left = (left + 7) % 8;
      leftLocation = this.destination(left);
      if (this.canStep(left)) break;
    }
    for (let i = 0; i < 8; i++) {
      right = (right + 1) % 8;
      rightLocation = this.destination(right);
      if (this.canStep(right)) break;
    }
    this.rotateRight = distanceSquared(leftLocation, this.target)
      >= distanceSquared(rightLocation, this.target);
  }

  step() {
    if (equal(this.location, this.target)) return;

    if (this.obstacle !== null) {
      const obstacleDirection = directionTo(this.location, this.obstacle);
      const state = `${key(this.location)}:${this.rotateRight}:${obstacleDirection}`;
      if (this.states.has(state)) this.reset(false);
      else this.states.add(state);
    }

    const distance = distanceSquared(this.location, this.target);
    if (distance < this.minDistance) {
      this.reset(false);
      this.minDistance = distance;
    }

    let direction = directionTo(this.location, this.target);
    if (this.obstacle !== null) direction = directionTo(this.location, this.obstacle);

    if (this.canStep(direction)) {
      const following = this.obstacle !== null;
      this.move(direction);
      if (following) {
        this.turnsToObstacle++;
        this.obstacle = this.destination(direction);
        if (this.turnsToObstacle >= 3 || !this.onMap(this.obstacle)) this.reset(false);
      }
      return;
    }

    this.turnsToObstacle = 0;
    this.chooseSide();
    for (let i = 0; i < 16; i++) {
      if (this.canStep(direction)) {
        this.move(direction);
        return;
      }
      const blocked = this.destination(direction);
      const [dx, dy] = DIRECTIONS[direction];
      const beyond = [blocked[0] + dx, blocked[1] + dy];
      if (!this.onMap(beyond)) this.rotateRight = !this.rotateRight;
      else this.obstacle = blocked;
      direction = (direction + (this.rotateRight ? 1 : 7)) % 8;
    }
  }
}

function connected(width, height, walls, start, target) {
  const queue = [start];
  const seen = new Set([key(start)]);
  for (let head = 0; head < queue.length; head++) {
    const current = queue[head];
    if (equal(current, target)) return true;
    for (const [dx, dy] of DIRECTIONS) {
      const next = [current[0] + dx, current[1] + dy];
      const nextKey = key(next);
      if (next[0] >= 0 && next[1] >= 0 && next[0] < width && next[1] < height
          && !walls.has(nextKey) && !seen.has(nextKey)) {
        seen.add(nextKey);
        queue.push(next);
      }
    }
  }
  return false;
}

function fails(width, height, walls, start, target, turns = 1200) {
  if (!connected(width, height, walls, start, target)) return false;
  const navigator = new Navigator(width, height, walls, start, target);
  for (let turn = 0; turn < turns; turn++) {
    navigator.step();
    if (equal(navigator.location, target)) return false;
  }
  return true;
}

function minimize(width, height, walls, start, target) {
  const result = new Set(walls);
  let changed = true;
  while (changed) {
    changed = false;
    for (const wall of [...result]) {
      const candidate = new Set(result);
      candidate.delete(wall);
      if (fails(width, height, candidate, start, target)) {
        result.delete(wall);
        changed = true;
      }
    }
  }
  return result;
}

function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x1_0000_0000;
  };
}

function render(width, height, walls, start, target) {
  for (let y = height - 1; y >= 0; y--) {
    let row = "";
    for (let x = 0; x < width; x++) {
      const cell = [x, y];
      row += equal(cell, start) ? "S" : equal(cell, target) ? "T"
        : walls.has(key(cell)) ? "#" : ".";
    }
    console.log(row);
  }
}

const width = 20;
const height = 20;
const random = seededRandom(16782);

let found = false;
for (let attempt = 1; attempt <= 400_000; attempt++) {
  const start = [Math.floor(random() * width), Math.floor(random() * height)];
  let target;
  do {
    target = [Math.floor(random() * width), Math.floor(random() * height)];
  } while (equal(start, target));
  const density = 0.05 + random() * 0.145;
  const walls = new Set();
  for (let x = 0; x < width; x++) {
    for (let y = 0; y < height; y++) {
      const cell = [x, y];
      if (!equal(cell, start) && !equal(cell, target) && random() < density) {
        walls.add(key(cell));
      }
    }
  }
  if (walls.size * 5 >= width * height) continue;
  if (fails(width, height, walls, start, target)) {
    const minimal = minimize(width, height, walls, start, target);
    console.log(`attempt=${attempt} walls=${minimal.size} start=${key(start)} target=${key(target)}`);
    console.log(`walls=${JSON.stringify([...minimal].sort())}`);
    render(width, height, minimal, start, target);
    found = true;
    break;
  }
}
if (!found) throw new Error("No counterexample found");
