package rtaa;

import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

/** Identical stationary-king behavior to the other path-planning bots. */
final class KingRatBot {
    private final RobotController rc;
    private boolean spawnedBaby;

    KingRatBot(RobotController rc) {
        this.rc = rc;
    }

    void runTurn() throws GameActionException {
        for (MapInfo info : rc.senseNearbyMapInfos()) {
            MapLocation location = info.getMapLocation();
            if (info.getCheeseAmount() > 0 && rc.canPickUpCheese(location)) {
                rc.pickUpCheese(location);
            }
        }

        if (!spawnedBaby) {
            for (MapLocation location :
                    rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 8)) {
                if (rc.canBuildRat(location)) {
                    rc.buildRat(location);
                    spawnedBaby = true;
                    break;
                }
            }
        }
    }
}
