package lrta;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

/**
 * Bounded-lookahead LRTA* (LSS-LRTA*) on an optimistic eight-neighbor grid.
 * A* builds a local search space, then a reverse shortest-path backup learns
 * h(s) = min_boundary(distance(s, boundary) + h(boundary)). With one expansion
 * this is the ordinary LRTA* one-step Bellman backup. Unlike RTAA*, learning
 * respects local obstacle distances rather than assigning f* - g(s).
 *
 * Primitive scratch, native string operations and unrolled neighbors follow
 * MPBasic's fast utilities. See README for sources and engine measurements.
 */
public final class LearningRealTimeAStar {
    /** Tuned below the 17,500-bytecode baby-rat limit on the project maps. */
    public static final int MAX_EXPANSIONS = 32;
    private static final int BYTECODE_RESERVE = 1_500;
    private static int learningReserve(int count) {
        return BYTECODE_RESERVE + 350 * count + 7 * count * count;
    }
    private static final int MAX_SEARCH_NODES = 1 + MAX_EXPANSIONS * 8;
    private static final int HEURISTIC_BITS = 12;
    private static final int HEURISTIC_MASK = (1 << HEURISTIC_BITS) - 1;
    private static final int INF = 1_000_000;

    private static final byte BLOCKED = 2;
    private static final byte OPEN = 1;
    private static final byte CLOSED = 2;

    private final RobotController rc;
    private final int width;
    private final int height;
    private final byte[] terrain;

    // Zero denotes an unseen entry; stored values are encoded as value + 1.
    private final StringBuilder adaptiveHeuristic;
    private final StringBuilder nodeByState;
    private final String emptyNodes;

    // Lookahead scratch is bounded by the expansions plus their neighbors.
    private final int[] state = new int[MAX_SEARCH_NODES];
    private final int[] g = new int[MAX_SEARCH_NODES];
    // Pack f, h, and state into one numeric key for OPEN's binary search.
    private final long[] priority = new long[MAX_SEARCH_NODES];
    private final int[] parent = new int[MAX_SEARCH_NODES];
    private final byte[] status = new byte[MAX_SEARCH_NODES];
    // Each node ID occupies one UTF-16 code unit. Native insert/delete shift
    // the queue at one charged bytecode instead of a Java heap-sifting loop.
    private final StringBuilder open = new StringBuilder(MAX_SEARCH_NODES);
    private final int[] expanded = new int[MAX_EXPANSIONS];
    private final int[] edges = new int[MAX_SEARCH_NODES * 8];
    private final int[] learnedDistance = new int[MAX_SEARCH_NODES];

    /** Last movement search's distinct expanded states, excluding frontier. */
    public int getLastExpansionCount() { return expandedCount; }

    private MapLocation target;
    private int targetIndex;
    private int nodeCount;
    private int openSize;
    private int expandedCount;

    public LearningRealTimeAStar(RobotController rc) {
        this.rc = rc;
        width = rc.getMapWidth();
        height = rc.getMapHeight();
        int tileCount = width * height;
        terrain = new byte[tileCount];
        emptyNodes = String.valueOf((char) 0).repeat(tileCount);
        nodeByState = new StringBuilder(emptyNodes);
        adaptiveHeuristic = new StringBuilder(emptyNodes);
    }

    public void reset(MapLocation newTarget) {
        target = newTarget;
        targetIndex = index(newTarget.x, newTarget.y);
        adaptiveHeuristic.replace(0, emptyNodes.length(), emptyNodes);
    }

    public void moveTo(MapLocation newTarget) throws GameActionException {
        expandedCount = 0;
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
        updateHeuristics();

        int firstNode = frontierNode;
        while (parent[firstNode] != 0 && parent[firstNode] != firstNode) {
            firstNode = parent[firstNode];
        }
        Direction direction = directionFrom(start, state[firstNode]);
        if (!step(direction)) {
            step(bestLegalNeighbor());
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

        int[] state = this.state;
        int[] g = this.g;
        long[] priority = this.priority;
        int[] parent = this.parent;
        byte[] status = this.status;
        StringBuilder nodeByState = this.nodeByState;
        int[] edges = this.edges;
        byte[] terrain = this.terrain;
        StringBuilder adaptiveHeuristic = this.adaptiveHeuristic;
        int width = this.width;
        int height = this.height;
        int nodeCount = this.nodeCount;
        int targetX = target.x;
        int targetY = target.y;
        int bestNode = -1;
        while (openSize > 0
                && expandedCount < MAX_EXPANSIONS
                && Clock.getBytecodesLeft() > learningReserve(expandedCount + 1) + 2_000) {
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
            boolean north = y + 1 < height;
            boolean south = y > 0;
            int edge = currentNode * 8;
            { // N: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (north) {
                    int neighborState = currentState + width;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 0] = neighborNode;
            }
            { // NE: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (north && east) {
                    int neighborState = currentState + width + 1;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 1] = neighborNode;
            }
            { // E: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (east) {
                    int neighborState = currentState + 1;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 2] = neighborNode;
            }
            { // SE: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (south && east) {
                    int neighborState = currentState - width + 1;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 3] = neighborNode;
            }
            { // S: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (south) {
                    int neighborState = currentState - width;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 4] = neighborNode;
            }
            { // SW: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (south && west) {
                    int neighborState = currentState - width - 1;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 5] = neighborNode;
            }
            { // W: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (west) {
                    int neighborState = currentState - 1;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 6] = neighborNode;
            }
            { // NW: intentionally inline to share cached arrays.
                int neighborNode = -1;
                if (north && west) {
                    int neighborState = currentState + width - 1;
                    if (terrain[neighborState] != BLOCKED) {
                        neighborNode = nodeByState.charAt(neighborState) - 1;
                        if (neighborNode < 0) {
                            neighborNode = nodeCount++;
                            state[neighborNode] = neighborState;
                            g[neighborNode] = nextCost;
                            int heuristic = adaptiveHeuristic.charAt(neighborState) - 1;
                            if (heuristic < 0) {
                                heuristic = Math.max(Math.abs(neighborState % width - targetX),
                                        Math.abs(neighborState / width - targetY));
                                adaptiveHeuristic.setCharAt(neighborState, (char) (heuristic + 1));
                            }
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            status[neighborNode] = OPEN;
                            nodeByState.setCharAt(neighborState, (char) (neighborNode + 1));
                            push(neighborNode);
                        } else if (status[neighborNode] == OPEN && nextCost < g[neighborNode]) {
                            g[neighborNode] = nextCost;
                            int heuristic = heuristicValue(priority[neighborNode]);
                            priority[neighborNode] = priorityKey(
                                    nextCost + heuristic, heuristic, neighborState);
                            parent[neighborNode] = currentNode;
                            decreaseKey(neighborNode);
                        }
                    }
                }
                edges[edge + 7] = neighborNode;
            }
        }
        if (bestNode < 0 && openSize > 0) {
            bestNode = open.charAt(0);
        }
        this.nodeCount = nodeCount;
        return bestNode;
    }

    private void updateHeuristics() {
        int[] expanded = this.expanded;
        int[] edges = this.edges;
        byte[] status = this.status;
        long[] priority = this.priority;
        int[] learnedDistance = this.learnedDistance;
        StringBuilder adaptiveHeuristic = this.adaptiveHeuristic;
        int[] state = this.state;
        int expandedCount = this.expandedCount;
        // Seed expanded cells from adjacent frontier h values. Only the small
        // expanded set needs Dijkstra; frontier nodes need no second queue.
        for (int i = expandedCount; --i >= 0;) {
            int node = expanded[i];
            int best = INF;
            int edge = node * 8;
            int n0 = edges[edge + 0];
            if (n0 >= 0 && status[n0] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n0]));
            }
            int n1 = edges[edge + 1];
            if (n1 >= 0 && status[n1] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n1]));
            }
            int n2 = edges[edge + 2];
            if (n2 >= 0 && status[n2] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n2]));
            }
            int n3 = edges[edge + 3];
            if (n3 >= 0 && status[n3] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n3]));
            }
            int n4 = edges[edge + 4];
            if (n4 >= 0 && status[n4] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n4]));
            }
            int n5 = edges[edge + 5];
            if (n5 >= 0 && status[n5] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n5]));
            }
            int n6 = edges[edge + 6];
            if (n6 >= 0 && status[n6] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n6]));
            }
            int n7 = edges[edge + 7];
            if (n7 >= 0 && status[n7] == OPEN) {
                best = Math.min(best, 1 + heuristicValue(priority[n7]));
            }
            learnedDistance[node] = best;
        }
        // Dense selection avoids sorting a second queue for this bounded set.
        // All expanded-to-expanded edges are symmetric.
        for (int remaining = expandedCount; remaining > 0; remaining--) {
            int closestPosition = -1;
            int best = INF;
            for (int i = remaining; --i >= 0;) {
                int node = expanded[i];
                if (learnedDistance[node] < best) {
                    closestPosition = i;
                    best = learnedDistance[node];
                }
            }
            if (closestPosition < 0) break;
            int closest = expanded[closestPosition];
            // Compact the unsettled prefix: no status check or rescanning of
            // settled nodes in minimum selection; at most k*(k+1)/2 probes.
            expanded[closestPosition] = expanded[remaining - 1];
            expanded[remaining - 1] = closest;
            status[closest] = 3; // settled
            int cell = state[closest];
            int previous = heuristicValue(priority[closest]);
            adaptiveHeuristic.setCharAt(cell, (char) (Math.max(previous, best) + 1));
            int edge = closest * 8;
            int candidate = best + 1;
            int n0 = edges[edge + 0];
            if (n0 >= 0 && status[n0] == CLOSED && candidate < learnedDistance[n0]) {
                learnedDistance[n0] = candidate;
            }
            int n1 = edges[edge + 1];
            if (n1 >= 0 && status[n1] == CLOSED && candidate < learnedDistance[n1]) {
                learnedDistance[n1] = candidate;
            }
            int n2 = edges[edge + 2];
            if (n2 >= 0 && status[n2] == CLOSED && candidate < learnedDistance[n2]) {
                learnedDistance[n2] = candidate;
            }
            int n3 = edges[edge + 3];
            if (n3 >= 0 && status[n3] == CLOSED && candidate < learnedDistance[n3]) {
                learnedDistance[n3] = candidate;
            }
            int n4 = edges[edge + 4];
            if (n4 >= 0 && status[n4] == CLOSED && candidate < learnedDistance[n4]) {
                learnedDistance[n4] = candidate;
            }
            int n5 = edges[edge + 5];
            if (n5 >= 0 && status[n5] == CLOSED && candidate < learnedDistance[n5]) {
                learnedDistance[n5] = candidate;
            }
            int n6 = edges[edge + 6];
            if (n6 >= 0 && status[n6] == CLOSED && candidate < learnedDistance[n6]) {
                learnedDistance[n6] = candidate;
            }
            int n7 = edges[edge + 7];
            if (n7 >= 0 && status[n7] == CLOSED && candidate < learnedDistance[n7]) {
                learnedDistance[n7] = candidate;
            }
        }
    }

    private Direction bestLegalNeighbor() {
        MapLocation current = rc.getLocation();
        Direction bestDirection = null;
        int bestValue = INF;
        Direction direction = Direction.NORTH;
        for (int remaining = 8; remaining-- > 0; direction = direction.rotateRight()) {
            MapLocation neighborLocation = current.add(direction);
            if (!rc.onTheMap(neighborLocation)) continue;
            int neighbor = index(neighborLocation.x, neighborLocation.y);
            int value = 1 + heuristic(neighbor);
            if (terrain[neighbor] != BLOCKED
                    && value < bestValue
                    && rc.canMove(direction)) {
                bestValue = value;
                bestDirection = direction;
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
        // Native replace clears the whole direct-address map at fixed cost.
        // Zero means unseen; node IDs are stored as id + 1 in one UTF-16 unit.
        nodeByState.replace(0, emptyNodes.length(), emptyNodes);
        nodeCount = 0;
        openSize = 0;
        open.delete(0, open.length());
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
        nodeByState.setCharAt(newState, (char) (node + 1));
        return node;
    }

    private int heuristic(int searchState) {
        int cached = adaptiveHeuristic.charAt(searchState) - 1;
        if (cached >= 0) return cached;
        int base = Math.max(Math.abs(searchState % width - target.x),
                Math.abs(searchState / width - target.y));
        adaptiveHeuristic.setCharAt(searchState, (char) (base + 1));
        return base;
    }

    private void push(int node) {
        StringBuilder open = this.open;
        long[] priority = this.priority;
        long key = priority[node];
        int low = 0;
        int high = openSize;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (priority[open.charAt(middle)] < key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        open.insert(low, (char) node);
        openSize++;
    }

    private int pop() {
        int node = open.charAt(0);
        open.deleteCharAt(0);
        openSize--;
        return node;
    }

    private void decreaseKey(int node) {
        // Unique one-character IDs make native indexOf an unambiguous lookup,
        // including ID zero and IDs above 255. No delimiter or position map.
        open.deleteCharAt(open.indexOf(String.valueOf((char) node)));
        openSize--;
        push(node);
    }

    private Direction directionFrom(int from, int to) {
        int fromX = from % width;
        int toX = to % width;
        int fromY = from / width;
        int toY = to / width;
        // The parent chain yields an adjacent first step (or CENTER).
        return Direction.fromDelta(toX - fromX, toY - fromY);
    }

    private static long priorityKey(int f, int h, int searchState) {
        return ((long) f << 24) | ((long) h << 12) | searchState;
    }

    private static int heuristicValue(long key) {
        return (int) (key >>> 12) & HEURISTIC_MASK;
    }

    private int index(int x, int y) {
        return y * width + x;
    }
}


