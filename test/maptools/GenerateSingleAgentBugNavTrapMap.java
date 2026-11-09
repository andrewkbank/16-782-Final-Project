package maptools;

import battlecode.common.Direction;
import battlecode.common.MapLocation;
import battlecode.common.RobotInfo;
import battlecode.common.Team;
import battlecode.common.UnitType;
import battlecode.world.GameMapIO;
import battlecode.world.LiveMap;
import battlecode.world.MapSymmetry;

import java.io.File;
import java.util.ArrayList;

/** Generates the minimal static-obstacle BugNav incompleteness counterexample. */
public final class GenerateSingleAgentBugNavTrapMap {
    private static final int SIZE = 20;

    private GenerateSingleAgentBugNavTrapMap() {}

    public static void main(String[] args) throws Exception {
        File outputDirectory = new File(args.length == 0 ? "maps" : args[0]);
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create " + outputDirectory);
        }

        boolean[] walls = new boolean[SIZE * SIZE];
        boolean[] dirt = new boolean[SIZE * SIZE];
        boolean[] cheeseMines = new boolean[SIZE * SIZE];
        int[] cheese = new int[SIZE * SIZE];

        // Team A's counterexample uses the two left-boundary walls. The two
        // right-boundary walls are only their required rotational mirror.
        setSymmetric(walls, 0, 3);
        setSymmetric(walls, 0, 4);

        cheeseMines[index(2, 7)] = true;
        cheeseMines[index(17, 12)] = true;

        RobotInfo[] kings = {
                new RobotInfo(1, Team.A, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, new MapLocation(2, 7),
                        Direction.NORTH, 0, 0, null),
                new RobotInfo(2, Team.B, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, new MapLocation(17, 12),
                        Direction.SOUTH, 1, 0, null)
        };

        LiveMap map = new LiveMap(
                SIZE,
                SIZE,
                new MapLocation(0, 0),
                16782,
                2000,
                "BugNavSingleAgentTrap",
                MapSymmetry.ROTATIONAL,
                walls,
                dirt,
                cheeseMines,
                cheese,
                new ArrayList<>(),
                new ArrayList<>(),
                kings);
        map.assertIsValid();
        GameMapIO.writeMap(map, outputDirectory);
        System.out.println("Wrote "
                + new File(outputDirectory, "BugNavSingleAgentTrap.map26"));
    }

    private static void setSymmetric(boolean[] values, int x, int y) {
        values[index(x, y)] = true;
        values[index(SIZE - 1 - x, SIZE - 1 - y)] = true;
    }

    private static int index(int x, int y) {
        return x + y * SIZE;
    }
}
