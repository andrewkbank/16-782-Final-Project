package concavebugnavbench;

import battlecode.common.*;
import pathplanner.BugNav;

/** Fresh-state, one-agent escape trial. Only team A builds a baby. */
public final class RobotPlayer {
    private RobotPlayer() {}

    public static void run(RobotController rc) {
        boolean king = rc.getType() == UnitType.RAT_KING;
        BugNav navigator = king ? null : new BugNav(rc);
        MapLocation target = king ? null : new MapLocation(rc.getLocation().x, 2);
        boolean spawned = false;
        boolean announced = false;
        while (true) {
            try {
                if (king) {
                    for (MapInfo info : rc.senseNearbyMapInfos()) {
                        MapLocation location = info.getMapLocation();
                        if (info.getCheeseAmount() > 0 && rc.canPickUpCheese(location)) {
                            rc.pickUpCheese(location);
                        }
                    }
                    if (rc.getTeam() == Team.A && !spawned) {
                        MapLocation spawn = new MapLocation(rc.getLocation().x, 6);
                        if (rc.canBuildRat(spawn)) {
                            rc.buildRat(spawn);
                            spawned = true;
                        }
                    }
                } else if (!announced) {
                    navigator.reset();
                    rc.setIndicatorString("PATH_TARGET:" + target.x + "," + target.y);
                    announced = true;
                } else {
                    navigator.moveTo(target);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
