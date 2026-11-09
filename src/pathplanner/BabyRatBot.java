package pathplanner;

import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

import java.util.Random;

/** A single explorer that repeatedly chooses a random map destination. */
final class BabyRatBot {
    private static final int TARGET_ATTEMPTS = 32;

    private final RobotController rc;
    private final Random random;
    private final BugNav navigator;
    private MapLocation target;
    private boolean targetNeedsAnnouncement;

    BabyRatBot(RobotController rc) {
        this.rc = rc;
        this.random = new Random(6147L + rc.getID());
        this.navigator = new BugNav(rc);
        chooseRandomTarget();
    }

    public void runTurn() throws GameActionException {
        if (target == null
                || rc.getLocation().equals(target)
                || targetIsKnownToBeBlocked()) {
            chooseRandomTarget();
        }

        // One target-change event is enough for the replay analyzer. Locations,
        // facing, cooldowns, and bytecodes are captured by the engine itself.
        // Staying still on this assignment round gives the benchmark an exact
        // start state without adding a per-turn bot log.
        if (targetNeedsAnnouncement) {
            rc.setIndicatorString("PATH_TARGET:" + target.x + "," + target.y);
            targetNeedsAnnouncement = false;
            return;
        }

        navigator.moveTo(target);
    }

    private boolean targetIsKnownToBeBlocked() throws GameActionException {
        return rc.canSenseLocation(target)
                && (!rc.sensePassability(target) || rc.isLocationOccupied(target));
    }

    private void chooseRandomTarget() {
        MapLocation current = rc.getLocation();
        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            MapLocation candidate = new MapLocation(
                    random.nextInt(rc.getMapWidth()),
                    random.nextInt(rc.getMapHeight()));
            if (!candidate.equals(current)) {
                target = candidate;
                navigator.reset();
                targetNeedsAnnouncement = true;
                return;
            }
        }

        // This is only reachable on a degenerate one-tile map, which BC26 disallows.
        target = new MapLocation(
                (current.x + 1) % rc.getMapWidth(),
                current.y);
        navigator.reset();
        targetNeedsAnnouncement = true;
    }
}
