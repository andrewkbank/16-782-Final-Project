package trafficbench;

import battlecode.common.*;
import pathplanner.BugNav;
import rtaa.RealTimeAdaptiveAStar;
import lrta.LearningRealTimeAStar;

/** Shared traffic behavior; the production movement planners are unchanged. */
public final class TrafficTrial {
    public static final int WIDTH = 48, HEIGHT = 33, RELEASE = 25, ROUNDS = 600;
    public static final String[] MODES = {"solo", "platoon8", "opposed2", "opposed8", "solob"};
    private TrafficTrial() {}

    public static int count(int mode, boolean left) {
        return switch (mode) {
            case 0 -> left ? 1 : 0;
            case 1 -> left ? 8 : 0;
            case 2 -> 1;
            case 3 -> 4;
            case 4 -> left ? 0 : 1;
            default -> throw new IllegalArgumentException("Unknown traffic mode");
        };
    }
    public static MapLocation king(boolean left) { return new MapLocation(left ? 15 : 32, left ? 12 : 20); }
    public static MapLocation spawn(boolean left, int rank) {
        int dx = rank < 5 ? 2 : rank < 7 ? 1 : 0;
        int dy = switch (rank) { case 0 -> 0; case 1 -> -1; case 2 -> 1;
            case 3, 5, 7 -> -2; case 4, 6 -> 2; default -> throw new IllegalArgumentException(); };
        return new MapLocation(left ? 15 + dx : 32 - dx, left ? 12 + dy : 20 - dy);
    }
    public static MapLocation goal(boolean left, int rank) {
        int x = 40 + 2 * (rank / 4), y = 12 + rank % 4;
        return new MapLocation(left ? x : 47 - x, left ? y : 32 - y);
    }

    public static void run(RobotController rc, int mode, int planner) {
        boolean isKing = rc.getType() == UnitType.RAT_KING, left = rc.getTeam() == Team.A;
        BugNav bug = !isKing && planner == 0 ? new BugNav(rc) : null;
        RealTimeAdaptiveAStar rtaa = !isKing && planner == 1 ? new RealTimeAdaptiveAStar(rc) : null;
        LearningRealTimeAStar lrta = !isKing && planner == 2 ? new LearningRealTimeAStar(rc) : null;
        int rank = -1, built = 0;
        if (!isKing) {
            for (int i = 0; i < count(mode, left); i++) {
                if (rc.getLocation().equals(spawn(left, i))) rank = i;
            }
            if (rank < 0) throw new IllegalStateException("Unexpected traffic spawn");
        }
        MapLocation target = isKing ? null : goal(left, rank);
        boolean announced = false;
        while (true) {
            try {
                if (isKing) {
                    // The serialized map does not preserve a shorter round limit.
                    // End the match after every robot has had its round-600 turn.
                    if (rc.getRoundNum() > ROUNDS) rc.disintegrate();
                    for (MapInfo info : rc.senseNearbyMapInfos()) {
                        if (info.getCheeseAmount() > 0 && rc.canPickUpCheese(info.getMapLocation())) {
                            rc.pickUpCheese(info.getMapLocation());
                        }
                    }
                    if (built < count(mode, left) && rc.canBuildRat(spawn(left, built))) {
                        rc.buildRat(spawn(left, built));
                        built++;
                    }
                } else if (rc.getRoundNum() >= RELEASE && rc.getRoundNum() <= ROUNDS) {
                    if (!announced) {
                        switch (planner) {
                            case 0 -> bug.reset();
                            case 1 -> rtaa.reset(target);
                            case 2 -> lrta.reset(target);
                        }
                        rc.setIndicatorString("PATH_TARGET:" + target.x + "," + target.y);
                        announced = true;
                    } else {
                        switch (planner) {
                            case 0 -> bug.moveTo(target);
                            case 1 -> rtaa.moveTo(target);
                            case 2 -> lrta.moveTo(target);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
}
