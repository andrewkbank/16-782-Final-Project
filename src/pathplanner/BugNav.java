package pathplanner;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight BugNav 2 obstacle follower.
 *
 * <p>Adapted from Ivan Geffner's BC26 {@code basic51/BugNav.java}, commit
 * 70b3f40cebefcca9dc858771715d78088e7ae520:
 * https://github.com/IvanGeffner/BC26/blob/70b3f40cebefcca9dc858771715d78088e7ae520/basic51/BugNav.java
 *
 * <p>The obstacle/rotation/reset logic is kept intentionally close to the
 * upstream implementation. The movement primitive is specialized for this
 * project: the rat turns toward the selected direction, re-checks that a
 * forward move is legal, and only then moves forward.
 */
public final class BugNav {
    private static final int INF = 1_000_000;
    private static final int MAX_TURNS_MOVING_TO_OBSTACLE = 3;

    private final RobotController rc;
    private final Set<Integer> states = new HashSet<>();

    private int turnsMovingToObstacle;
    private Boolean rotateRight = true;
    private MapLocation lastObstacleFound;
    private int minDistToTarget = INF;
    private MapLocation previousTarget;

    public BugNav(RobotController rc) {
        this.rc = rc;
    }

    public void moveTo(MapLocation target) throws GameActionException {
        if (target == null || rc.getLocation().equals(target)) {
            return;
        }
        if (!rc.isMovementReady()) {
            return;
        }

        int distanceBetweenTargets = 0;
        if (previousTarget == null) {
            resetPathfinding(true);
        } else {
            distanceBetweenTargets = previousTarget.distanceSquaredTo(target);
        }
        previousTarget = target;

        if (distanceBetweenTargets > 2) {
            resetPathfinding(true);
        } else if (distanceBetweenTargets > 0) {
            resetPathfinding(false);
        }

        checkForLoop();

        MapLocation current = rc.getLocation();
        int distance = current.distanceSquaredTo(target);
        if (distance < minDistToTarget) {
            resetPathfinding(false);
            minDistToTarget = distance;
        }

        Direction direction = current.directionTo(target);
        if (lastObstacleFound != null) {
            direction = current.directionTo(lastObstacleFound);
        }

        if (canStep(direction)) {
            if (step(direction) && lastObstacleFound != null) {
                turnsMovingToObstacle++;
                lastObstacleFound = rc.getLocation().add(direction);
                if (turnsMovingToObstacle >= MAX_TURNS_MOVING_TO_OBSTACLE
                        || !rc.onTheMap(lastObstacleFound)) {
                    resetPathfinding(false);
                }
            }
            return;
        }
        turnsMovingToObstacle = 0;

        chooseWallFollowSide();

        // Try both wall-follow directions if the map edge forces a side swap.
        for (int i = 0; i < 16; i++) {
            if (canStep(direction)) {
                step(direction);
                return;
            }

            MapLocation blocked = current.add(direction);
            if (!rc.onTheMap(blocked.add(direction))) {
                rotateRight = !rotateRight;
            } else {
                lastObstacleFound = blocked;
            }
            direction = rotateRight ? direction.rotateRight() : direction.rotateLeft();
        }
    }

    public void reset() {
        previousTarget = null;
        resetPathfinding(true);
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

        // Turning can change the legality/cooldown state, so never rely only on
        // the earlier directional check.
        if (rc.isMovementReady() && rc.canMoveForward()) {
            rc.moveForward();
            return true;
        }
        return false;
    }

    private void resetPathfinding(boolean resetRotation) {
        lastObstacleFound = null;
        minDistToTarget = INF;
        turnsMovingToObstacle = 0;
        states.clear();
        if (resetRotation) {
            rotateRight = null;
        }
    }

    private void checkForLoop() {
        if (lastObstacleFound == null) {
            return;
        }

        int side = Boolean.FALSE.equals(rotateRight) ? 1 : 0;
        Direction obstacleDirection = rc.getLocation().directionTo(lastObstacleFound);
        int code = (rc.getLocation().x << 6)
                | rc.getLocation().y
                | (side << 15)
                | (obstacleDirection.ordinal() << 12);
        if (!states.add(code)) {
            resetPathfinding(false);
        }
    }

    private void chooseWallFollowSide() {
        if (rotateRight != null) {
            return;
        }

        MapLocation current = rc.getLocation();
        Direction direct = current.directionTo(previousTarget);
        Direction left = direct;
        Direction right = direct;
        MapLocation leftLocation = current;
        MapLocation rightLocation = current;

        for (int i = 0; i < 8; i++) {
            left = left.rotateLeft();
            leftLocation = current.add(left);
            if (canStep(left)) {
                break;
            }
        }
        for (int i = 0; i < 8; i++) {
            right = right.rotateRight();
            rightLocation = current.add(right);
            if (canStep(right)) {
                break;
            }
        }

        rotateRight = leftLocation.distanceSquaredTo(previousTarget)
                >= rightLocation.distanceSquaredTo(previousTarget);
    }
}
