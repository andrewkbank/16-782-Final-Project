package lrtabench;

import battlecode.common.Clock;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.UnitType;
import lrta.LearningRealTimeAStar;

/**
 * Deterministic LRTA* benchmark entry point.
 *
 * <p>Unlike the exploratory {@code lrta} bot, targets change on fixed round
 * boundaries and are derived only from the map and spawn location. Therefore
 * every expansion-cap run receives exactly the same targets at the same time,
 * even when one cap reaches an earlier target sooner than another.
 */
public final class RobotPlayer {
    private static final int TARGET_WINDOW_ROUNDS = 200;

    private RobotPlayer() {}

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        boolean king = rc.getType() == UnitType.RAT_KING;
        LearningRealTimeAStar navigator = king ? null : new LearningRealTimeAStar(rc);
        MapLocation spawn = rc.getLocation();
        int targetEpoch = -1;
        MapLocation target = null;
        boolean spawnedBaby = false;

        while (true) {
            try {
                if (king) {
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
                } else {
                    int epoch = (rc.getRoundNum() - 1) / TARGET_WINDOW_ROUNDS;
                    if (epoch != targetEpoch) {
                        targetEpoch = epoch;
                        target = deterministicTarget(
                                spawn, rc.getMapWidth(), rc.getMapHeight(), epoch);
                        navigator.reset(target);
                        rc.setIndicatorString("PATH_TARGET:" + target.x + "," + target.y);
                    } else {
                        navigator.moveTo(target);
                        rc.setIndicatorString("LRTA_EXPANDED:" + navigator.getLastExpansionCount());
                    }
                    rc.setIndicatorDot(target, 255, 64, 255);
                }
            } catch (GameActionException e) {
                System.out.println("Illegal action on round " + rc.getRoundNum());
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Unexpected exception on round " + rc.getRoundNum());
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    private static MapLocation deterministicTarget(
            MapLocation spawn, int width, int height, int epoch) {
        int value = spawn.x * 0x1f1f1f1f
                ^ spawn.y * 0x6d2b79f5
                ^ width * 0x45d9f3b
                ^ height * 0x119de1f3
                ^ epoch * 0x27d4eb2d;
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        int x = Math.floorMod(value, width);
        value ^= value >>> 16;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        int y = Math.floorMod(value, height);
        return new MapLocation(x, y);
    }
}
