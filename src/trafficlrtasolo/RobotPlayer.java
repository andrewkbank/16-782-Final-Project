package trafficlrtasolo;

import battlecode.common.RobotController;
import trafficbench.TrafficTrial;

/** Shared traffic trial: lrta, solo. */
public final class RobotPlayer {
    public static void run(RobotController rc) { TrafficTrial.run(rc, 0, 2); }
}
