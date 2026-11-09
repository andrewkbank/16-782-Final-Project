package bugnavcounterexample;

import battlecode.common.Clock;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.Team;
import battlecode.common.UnitType;
import pathplanner.BugNav;

/** Fixed-start harness for the static, single-moving-agent BugNav counterexample. */
public final class RobotPlayer {
    private RobotPlayer() {}

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        if (rc.getType() == UnitType.RAT_KING) {
            runKing(rc);
        } else {
            runBaby(rc);
        }
    }

    private static void runKing(RobotController rc) {
        boolean spawned = false;
        MapLocation spawn = rc.getTeam() == Team.A
                ? new MapLocation(0, 5)
                : new MapLocation(19, 14);
        while (true) {
            try {
                for (MapInfo info : rc.senseNearbyMapInfos()) {
                    if (info.getCheeseAmount() > 0
                            && rc.canPickUpCheese(info.getMapLocation())) {
                        rc.pickUpCheese(info.getMapLocation());
                    }
                }
                if (!spawned && rc.canBuildRat(spawn)) {
                    rc.buildRat(spawn);
                    spawned = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    private static void runBaby(RobotController rc) {
        MapLocation target = rc.getTeam() == Team.A
                ? new MapLocation(0, 2)
                : new MapLocation(19, 17);
        BugNav navigator = new BugNav(rc);
        boolean announced = false;
        while (true) {
            try {
                if (!announced) {
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
