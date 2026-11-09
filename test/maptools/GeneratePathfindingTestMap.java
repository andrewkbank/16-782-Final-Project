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

/** Reproducibly generates maps/BugNavTest.map26. */
public final class GeneratePathfindingTestMap {
    private static final int WIDTH = 24;
    private static final int HEIGHT = 24;

    private GeneratePathfindingTestMap() {}

    public static void main(String[] args) throws Exception {
        File outputDirectory = new File(args.length == 0 ? "maps" : args[0]);
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create " + outputDirectory);
        }

        boolean[] walls = new boolean[WIDTH * HEIGHT];
        boolean[] dirt = new boolean[WIDTH * HEIGHT];
        boolean[] cheeseMines = new boolean[WIDTH * HEIGHT];
        int[] cheese = new int[WIDTH * HEIGHT];

        // Each barrier has multiple gaps, so BugNav must wall-follow but can
        // always make progress. Add walls in rotationally symmetric pairs.
        for (int y = 4; y <= 19; y++) {
            if (y != 7 && y != 16) {
                setSymmetric(walls, 8, y);
            }
        }
        for (int y = 6; y <= 17; y++) {
            if (y != 10 && y != 13) {
                setSymmetric(walls, 11, y);
            }
        }
        for (int x = 4; x <= 9; x++) {
            if (x != 6) {
                setSymmetric(walls, x, 11);
            }
        }

        // Each mine is at the center of its 3x3 king. The kings never need to
        // move to collect cheese spawned around their mine.
        cheeseMines[index(3, 3)] = true;
        cheeseMines[index(20, 20)] = true;

        RobotInfo[] kings = {
                new RobotInfo(1, Team.A, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, new MapLocation(3, 3),
                        Direction.NORTHEAST, 0, 0, null),
                new RobotInfo(2, Team.B, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, new MapLocation(20, 20),
                        Direction.SOUTHWEST, 1, 0, null)
        };

        LiveMap map = new LiveMap(
                WIDTH,
                HEIGHT,
                new MapLocation(0, 0),
                6147,
                2000,
                "BugNavTest",
                MapSymmetry.ROTATIONAL,
                walls,
                dirt,
                cheeseMines,
                cheese,
                new ArrayList<>(), // No cats.
                new ArrayList<>(),
                kings);
        map.assertIsValid();
        GameMapIO.writeMap(map, outputDirectory);
        System.out.println("Wrote " + new File(outputDirectory, "BugNavTest.map26"));
    }

    private static void setSymmetric(boolean[] values, int x, int y) {
        values[index(x, y)] = true;
        values[index(WIDTH - 1 - x, HEIGHT - 1 - y)] = true;
    }

    private static int index(int x, int y) {
        return x + y * WIDTH;
    }
}
