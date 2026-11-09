package classicbug2;

import battlecode.common.Clock;
import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.UnitType;

/** Entry point for the classical Bug2 variant of the path-planning bot. */
public final class RobotPlayer {
    private RobotPlayer() {}

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        KingRatBot king = rc.getType() == UnitType.RAT_KING ? new KingRatBot(rc) : null;
        BabyRatBot baby = king == null ? new BabyRatBot(rc) : null;
        while (true) {
            try {
                if (king != null) king.runTurn();
                else baby.runTurn();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
