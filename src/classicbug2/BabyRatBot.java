package classicbug2;

import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

import java.util.Random;

/** The existing random-target explorer using classical Bug2 navigation. */
final class BabyRatBot {
    private static final int TARGET_ATTEMPTS = 32;

    private final RobotController rc;
    private final Random random;
    private final ClassicBug2 navigator;
    private MapLocation target;
    private boolean targetNeedsAnnouncement;

    BabyRatBot(RobotController rc) {
        this.rc = rc;
        this.random = new Random(6147L + rc.getID());
        this.navigator = new ClassicBug2(rc);
        chooseRandomTarget();
    }

    public void runTurn() throws GameActionException {
        if (target == null
                || rc.getLocation().equals(target)
                || targetIsKnownToBeBlocked()
                || navigator.isUnreachable()) {
            chooseRandomTarget();
        }

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
                navigator.reset(target);
                targetNeedsAnnouncement = true;
                return;
            }
        }
        target = new MapLocation((current.x + 1) % rc.getMapWidth(), current.y);
        navigator.reset(target);
        targetNeedsAnnouncement = true;
    }
}
