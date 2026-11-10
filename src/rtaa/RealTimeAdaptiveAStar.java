package rtaa;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

import java.util.Arrays;

/**
 * Bounded Real-Time Adaptive A* (RTAA*) on the eight-neighbor Battlecode grid.
 *
 * <p>Each turn performs a limited A* lookahead, moves toward the best frontier
 * state, and raises the heuristic of expanded states by {@code f* - g(s)}.
 * Learned heuristics persist until the target changes. Unknown cells are
 * optimistically passable; sensed walls are retained in a compact map model.
 */
public final class RealTimeAdaptiveAStar {
    /** Tuned below the 17,500-bytecode baby-rat limit on the project maps. */
    public static final int MAX_EXPANSIONS = 14;
    private static final int BYTECODE_RESERVE = 4_000;
    private static final int MAX_SEARCH_NODES = 1 + MAX_EXPANSIONS * 8;
    private static final int HEURISTIC_BITS = 12;
    private static final int HEURISTIC_MASK = (1 << HEURISTIC_BITS) - 1;
    // Wide enough for expansion sweeps above the production cap. Keeping this
    // independent of MAX_EXPANSIONS prevents node IDs from spilling into the
    // generation prefix when a benchmark compiles a larger lookahead.
    private static final int NODE_BITS = 10;
    private static final int NODE_MASK = (1 << NODE_BITS) - 1;
    private static final int INF = 1_000_000;

    private static final byte BLOCKED = 2;
    private static final byte OPEN = 1;
    private static final byte CLOSED = 2;

    private static final Direction[] DIRECTIONS = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };
    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};

    private final RobotController rc;
    private final int width;
    private final int height;
    private final byte[] terrain;

    // The upper bits are a target/search generation, avoiding map-sized fills.
    private final int[] adaptiveHeuristic;
    private final int[] nodeByState;

    // Lookahead scratch is bounded by the expansions plus their neighbors.
    private final int[] state = new int[MAX_SEARCH_NODES];
    private final int[] g = new int[MAX_SEARCH_NODES];
    // Heap comparisons are extremely hot. Pack f, h, and state into one key so
    // each comparison is two array reads plus one long comparison.
    private final long[] priority = new long[MAX_SEARCH_NODES];
    private final int[] parent = new int[MAX_SEARCH_NODES];
    private final byte[] status = new byte[MAX_SEARCH_NODES];
    private final int[] heap = new int[MAX_SEARCH_NODES];
    private final int[] heapPosition = new int[MAX_SEARCH_NODES];
    private final int[] expanded = new int[MAX_EXPANSIONS];

    private MapLocation target;
    private int targetIndex;
    private int targetGeneration = 1;
    private int targetPrefix = 1 << HEURISTIC_BITS;
    private int searchGeneration = 1;
    private int searchPrefix = 1 << NODE_BITS;
    private int nodeCount;
    private int heapSize;
    private int expandedCount;

    public RealTimeAdaptiveAStar(RobotController rc) {
        this.rc = rc;
        width = rc.getMapWidth();
        height = rc.getMapHeight();
        int tileCount = width * height;
        terrain = new byte[tileCount];
        adaptiveHeuristic = new int[tileCount];
        nodeByState = new int[tileCount];
    }

    public void reset(MapLocation newTarget) {
        target = newTarget;
        targetIndex = index(newTarget.x, newTarget.y);
        targetGeneration++;
        if (targetGeneration >= (1 << (31 - HEURISTIC_BITS))) {
            Arrays.fill(adaptiveHeuristic, 0);
            targetGeneration = 1;
        }
        targetPrefix = targetGeneration << HEURISTIC_BITS;
    }

    public void moveTo(MapLocation newTarget) throws GameActionException {
        if (newTarget == null || rc.getLocation().equals(newTarget)) {
            return;
        }
        if (!newTarget.equals(target)) {
            reset(newTarget);
        }

        observeTerrain();
        if (!rc.isMovementReady()) {
            return;
        }

        int start = index(rc.getLocation().x, rc.getLocation().y);
        int frontierNode = limitedLookahead(start);
        if (frontierNode < 0) {
            return;
        }
        updateHeuristics(frontierNode);

        int firstNode = frontierNode;
        while (parent[firstNode] != 0 && parent[firstNode] != firstNode) {
            firstNode = parent[firstNode];
        }
        Direction direction = directionFrom(start, state[firstNode]);
        if (!step(direction)) {
            step(bestLegalNeighbor(start));
        }
    }

    private void observeTerrain() {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        for (int i = nearby.length; --i >= 0;) {
            MapInfo info = nearby[i];
            MapLocation location = info.getMapLocation();
            terrain[index(location.x, location.y)] = info.isPassable() ? (byte) 1 : BLOCKED;
        }
    }

    private int limitedLookahead(int start) {
        beginSearch();
        int startNode = discover(start, 0, 0);
        parent[startNode] = startNode;
        push(startNode);

        int bestNode = -1;
        while (heapSize > 0
                && expandedCount < MAX_EXPANSIONS
                && Clock.getBytecodesLeft() > BYTECODE_RESERVE) {
            int currentNode = pop();
            int currentState = state[currentNode];
            if (currentState == targetIndex) {
                bestNode = currentNode;
                break;
            }
            status[currentNode] = CLOSED;
            expanded[expandedCount++] = currentNode;

            int x = currentState % width;
            int y = currentState / width;
            int nextCost = g[currentNode] + 1;
            boolean east = x + 1 < width;
            boolean west = x > 0;
            if (y + 1 < height) {
                visitNeighbor(currentState + width, nextCost, currentNode);
                if (east) {
                    visitNeighbor(currentState + width + 1, nextCost, currentNode);
                }
            }
            if (east) {
                visitNeighbor(currentState + 1, nextCost, currentNode);
                if (y > 0) {
                    visitNeighbor(currentState - width + 1, nextCost, currentNode);
                }
            }
            if (y > 0) {
                visitNeighbor(currentState - width, nextCost, currentNode);
                if (west) {
                    visitNeighbor(currentState - width - 1, nextCost, currentNode);
                }
            }
            if (west) {
                visitNeighbor(currentState - 1, nextCost, currentNode);
                if (y + 1 < height) {
                    visitNeighbor(currentState + width - 1, nextCost, currentNode);
                }
            }
        }
        if (bestNode < 0 && heapSize > 0) {
            bestNode = heap[0];
        }
        return bestNode;
    }

    private void visitNeighbor(int neighborState, int nextCost, int currentNode) {
        if (terrain[neighborState] == BLOCKED) {
            return;
        }
        int packed = nodeByState[neighborState];
        int neighborNode = (packed & ~NODE_MASK) == searchPrefix
                ? (packed & NODE_MASK) - 1
                : -1;
        if (neighborNode < 0) {
            neighborNode = nodeCount++;
            state[neighborNode] = neighborState;
            g[neighborNode] = nextCost;
            int heuristic = heuristic(neighborState);
            priority[neighborNode] = priorityKey(
                    nextCost + heuristic, heuristic, neighborState);
            parent[neighborNode] = currentNode;
            status[neighborNode] = OPEN;
            nodeByState[neighborState] = searchPrefix | (neighborNode + 1);
            push(neighborNode);
        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
            g[neighborNode] = nextCost;
            int heuristic = heuristicValue(priority[neighborNode]);
            priority[neighborNode] = priorityKey(
                    nextCost + heuristic, heuristic, neighborState);
            parent[neighborNode] = currentNode;
            siftUp(heapPosition[neighborNode]);
        }
    }

    private void updateHeuristics(int frontierNode) {
        int bestF = fValue(priority[frontierNode]);
        for (int i = expandedCount; --i >= 0;) {
            int node = expanded[i];
            int learned = bestF - g[node];
            int searchState = state[node];
            int packed = adaptiveHeuristic[searchState];
            int previous = (packed & ~HEURISTIC_MASK) == targetPrefix
                    ? packed & HEURISTIC_MASK
                    : 0;
            if (learned > previous) {
                adaptiveHeuristic[searchState] = targetPrefix | learned;
            }
        }
    }

    private Direction bestLegalNeighbor(int start) {
        int x = start % width;
        int y = start / width;
        Direction bestDirection = null;
        int bestValue = INF;
        for (int direction = 0; direction < 8; direction++) {
            int nx = x + DX[direction];
            int ny = y + DY[direction];
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                continue;
            }
            int neighbor = index(nx, ny);
            int value = 1 + heuristic(neighbor);
            if (terrain[neighbor] != BLOCKED
                    && value < bestValue
                    && rc.canMove(DIRECTIONS[direction])) {
                bestValue = value;
                bestDirection = DIRECTIONS[direction];
            }
        }
        return bestDirection;
    }

    private boolean step(Direction direction) throws GameActionException {
        if (direction == null
                || direction == Direction.CENTER) {
            return false;
        }
        if (rc.getDirection() != direction) {
            // Check the post-turn move before spending the turn action. If the
            // planned tile was an unseen wall, the caller can still choose a
            // legal fallback direction this turn.
            if (!rc.canMove(direction)
                    || !rc.isTurningReady()
                    || !rc.canTurn(direction)) {
                return false;
            }
            rc.turn(direction);
        }
        if (rc.canMoveForward()) {
            rc.moveForward();
            return true;
        }
        return false;
    }

    private void beginSearch() {
        searchGeneration++;
        if (searchGeneration >= (1 << (31 - NODE_BITS))) {
            Arrays.fill(nodeByState, 0);
            searchGeneration = 1;
        }
        searchPrefix = searchGeneration << NODE_BITS;
        nodeCount = 0;
        heapSize = 0;
        expandedCount = 0;
    }

    private int discover(int newState, int cost, int predecessor) {
        int node = nodeCount++;
        state[node] = newState;
        g[node] = cost;
        int heuristic = heuristic(newState);
        priority[node] = priorityKey(cost + heuristic, heuristic, newState);
        parent[node] = predecessor;
        status[node] = OPEN;
        nodeByState[newState] = searchPrefix | (node + 1);
        return node;
    }

    private int heuristic(int searchState) {
        int x = searchState % width;
        int y = searchState / width;
        int base = Math.max(Math.abs(x - target.x), Math.abs(y - target.y));
        int packed = adaptiveHeuristic[searchState];
        int learned = (packed & ~HEURISTIC_MASK) == targetPrefix
                ? packed & HEURISTIC_MASK
                : 0;
        return Math.max(base, learned);
    }

    private void push(int node) {
        int position = heapSize++;
        heap[position] = node;
        heapPosition[node] = position;
        siftUp(position);
    }

    private int pop() {
        int result = heap[0];
        int replacement = heap[--heapSize];
        if (heapSize > 0) {
            heap[0] = replacement;
            heapPosition[replacement] = 0;
            siftDown(0);
        }
        return result;
    }

    private void siftUp(int position) {
        int node = heap[position];
        while (position > 0) {
            int parentPosition = (position - 1) >>> 1;
            int parentNode = heap[parentPosition];
            if (priority[node] >= priority[parentNode]) {
                break;
            }
            heap[position] = parentNode;
            heapPosition[parentNode] = position;
            position = parentPosition;
        }
        heap[position] = node;
        heapPosition[node] = position;
    }

    private void siftDown(int position) {
        int node = heap[position];
        while (true) {
            int left = position * 2 + 1;
            if (left >= heapSize) {
                break;
            }
            int right = left + 1;
            int child = right < heapSize
                    && priority[heap[right]] < priority[heap[left]] ? right : left;
            if (priority[heap[child]] >= priority[node]) {
                break;
            }
            heap[position] = heap[child];
            heapPosition[heap[position]] = position;
            position = child;
        }
        heap[position] = node;
        heapPosition[node] = position;
    }

    private Direction directionFrom(int from, int to) {
        int fromX = from % width;
        int toX = to % width;
        int fromY = from / width;
        int toY = to / width;
        return Direction.fromDelta(
                toX > fromX ? 1 : toX < fromX ? -1 : 0,
                toY > fromY ? 1 : toY < fromY ? -1 : 0);
    }

    private static long priorityKey(int f, int h, int searchState) {
        return ((long) f << 24) | ((long) h << 12) | searchState;
    }

    private static int fValue(long key) {
        return (int) (key >>> 24);
    }

    private static int heuristicValue(long key) {
        return (int) (key >>> 12) & HEURISTIC_MASK;
    }

    private int index(int x, int y) {
        return y * width + x;
    }
}
