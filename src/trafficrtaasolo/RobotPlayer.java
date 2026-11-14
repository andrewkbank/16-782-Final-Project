package trafficrtaasolo;

import battlecode.common.RobotController;
import trafficbench.TrafficTrial;

/** Shared traffic trial: rtaa, solo. */
public final class RobotPlayer {
    public static void run(RobotController rc) { TrafficTrial.run(rc, 0, 1); }
}
