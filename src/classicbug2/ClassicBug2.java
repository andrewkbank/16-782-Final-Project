package classicbug2;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

/**
 * A grid adaptation of the classical Bug2 algorithm for a static point robot.
 * Map edges are treated like any other obstacle boundary, and wall-following
 * handedness never changes during an obstacle encounter.
 */
public final class ClassicBug2 {
    private final RobotController rc;

    private MapLocation target;
    private MapLocation mLineStart;
    private MapLocation hitPoint;
    private int hitDistance;
    private MapLocation obstacleContact;
    private int boundarySteps;
    private boolean followingBoundary;
    private boolean unreachable;
    private boolean crossedMLine;

    public ClassicBug2(RobotController rc) {
        this.rc = rc;
    }

    public void moveTo(MapLocation newTarget) throws GameActionException {
        if (newTarget == null || rc.getLocation().equals(newTarget)) {
            return;
        }
        if (!newTarget.equals(target)) {
            reset(newTarget);
        }
        if (unreachable || !rc.isMovementReady()) {
            return;
        }

        if (followingBoundary) {
            if (boundarySteps > 0
                    && crossedMLine
                    && rc.getLocation().distanceSquaredTo(target) < hitDistance
                    && canStep(rc.getLocation().directionTo(target))) {
                followingBoundary = false;
            } else {
                if (boundarySteps > 1 && rc.getLocation().equals(hitPoint)) {
                    unreachable = true;
                    return;
                }
                followBoundary();
                return;
            }
        }

        Direction direct = rc.getLocation().directionTo(target);
        if (canStep(direct)) {
            step(direct);
            return;
        }

        followingBoundary = true;
        hitPoint = rc.getLocation();
        hitDistance = hitPoint.distanceSquaredTo(target);
        obstacleContact = rc.getLocation().add(direct);
        boundarySteps = 0;
        crossedMLine = false;
        followBoundary();
    }

    public void reset(MapLocation newTarget) {
        target = newTarget;
        mLineStart = rc.getLocation();
        hitPoint = null;
        hitDistance = 0;
        obstacleContact = null;
        boundarySteps = 0;
        followingBoundary = false;
        unreachable = false;
        crossedMLine = false;
    }

    public boolean isUnreachable() {
        return unreachable;
    }

    private void followBoundary() throws GameActionException {
        // Keep the most recently encountered blocked tile as the boundary
        // contact. Rotate consistently counterclockwise around that contact;
        // map-edge locations participate exactly like blocked map tiles.
        Direction candidate = rc.getLocation().directionTo(obstacleContact);
        for (int i = 0; i < 8; i++) {
            if (canStep(candidate)) {
                MapLocation before = rc.getLocation();
                if (step(candidate)) {
                    boundarySteps++;
                    crossedMLine = crossesMLine(before, rc.getLocation());
                }
                return;
            }
            obstacleContact = rc.getLocation().add(candidate);
            candidate = candidate.rotateLeft();
        }
        unreachable = true;
    }

    private long mLineCross(MapLocation location) {
        long dx = target.x - mLineStart.x;
        long dy = target.y - mLineStart.y;
        long px = location.x - mLineStart.x;
        long py = location.y - mLineStart.y;
        return px * dy - py * dx;
    }

    private boolean crossesMLine(MapLocation before, MapLocation after) {
        long beforeCross = mLineCross(before);
        long afterCross = mLineCross(after);
        long dx = target.x - mLineStart.x;
        long dy = target.y - mLineStart.y;

        // Detect both an exact segment crossing and a cell intersected by the
        // ideal line. The latter handles grid quantization at shallow slopes.
        return beforeCross == 0
                || afterCross == 0
                || (beforeCross < 0) != (afterCross < 0)
                || 2L * Math.abs(afterCross) <= Math.abs(dx) + Math.abs(dy);
    }

    private boolean canStep(Direction direction) {
        return direction != null
                && direction != Direction.CENTER
                && rc.isMovementReady()
                && rc.canMove(direction);
    }

    private boolean step(Direction direction) throws GameActionException {
        if (!canStep(direction)) {
            return false;
        }
        if (rc.getDirection() != direction) {
            if (!rc.isTurningReady() || !rc.canTurn(direction)) {
                return false;
            }
            rc.turn(direction);
        }
        if (rc.isMovementReady() && rc.canMoveForward()) {
            rc.moveForward();
            return true;
        }
        return false;
    }
}
