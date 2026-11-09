package pathplanner;

import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;

/** A stationary king that feeds itself and creates exactly one explorer. */
final class KingRatBot {
    private final RobotController rc;
    private boolean spawnedBaby;

    KingRatBot(RobotController rc) {
        this.rc = rc;
    }

    public void runTurn() throws GameActionException {
        collectNearbyCheese();

        if (!spawnedBaby) {
            spawnedBaby = trySpawnBaby();
        }

        rc.setIndicatorString(spawnedBaby
                ? "Stationary king: feeding; explorer deployed"
                : "Stationary king: feeding; waiting to deploy explorer");
    }

    private void collectNearbyCheese() throws GameActionException {
        for (MapInfo info : rc.senseNearbyMapInfos()) {
            MapLocation location = info.getMapLocation();
            if (info.getCheeseAmount() > 0 && rc.canPickUpCheese(location)) {
                rc.pickUpCheese(location);
            }
        }
    }

    private boolean trySpawnBaby() throws GameActionException {
        MapLocation king = rc.getLocation();
        for (MapLocation location : rc.getAllLocationsWithinRadiusSquared(king, 8)) {
            if (rc.canBuildRat(location)) {
                rc.buildRat(location);
                return true;
            }
        }
        return false;
    }
}
