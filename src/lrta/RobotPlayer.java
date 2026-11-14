package lrta;

import battlecode.common.Clock;
import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.UnitType;

/** Entry point for the Learning Real-Time A* path-planning bot. */
public final class RobotPlayer {
    private RobotPlayer() {}

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        KingRatBot king = rc.getType() == UnitType.RAT_KING ? new KingRatBot(rc) : null;
        BabyRatBot baby = king == null ? new BabyRatBot(rc) : null;

        while (true) {
            try {
                if (king != null) {
                    king.runTurn();
                } else {
                    baby.runTurn();
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
}
