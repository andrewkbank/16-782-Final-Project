package concavedartaabench;

import battlecode.common.RobotController;
import rtaa.RealTimeAdaptiveAStar;

public final class RobotPlayer {
    private RobotPlayer() {}

    public static void run(RobotController rc) {
        concavertaabench.RobotPlayer.run(rc, RealTimeAdaptiveAStar.Mode.MOVE_TO_BORDER);
    }
}
