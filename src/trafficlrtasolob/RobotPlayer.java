package trafficlrtasolob;

import battlecode.common.RobotController;
import trafficbench.TrafficTrial;

/** Solo control entering from the right chamber. */
public final class RobotPlayer {
    public static void run(RobotController rc) { TrafficTrial.run(rc, 4, 2); }
}
