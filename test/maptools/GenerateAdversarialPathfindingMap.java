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

/** Generates a symmetric maze of one-wide corridors and long dead ends. */
public final class GenerateAdversarialPathfindingMap {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 32;

    private GenerateAdversarialPathfindingMap() {}

    public static void main(String[] args) throws Exception {
        File outputDirectory = new File(args.length == 0 ? "maps" : args[0]);
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create " + outputDirectory);
        }

        boolean[] walls = new boolean[WIDTH * HEIGHT];
        boolean[] dirt = new boolean[WIDTH * HEIGHT];
        boolean[] cheeseMines = new boolean[WIDTH * HEIGHT];
        int[] cheese = new int[WIDTH * HEIGHT];

        // Long vertical U shapes. Their interiors are one tile wide, and each
        // has only one entrance at the end opposite its cap.
        addVerticalDeadEnd(walls, 8, 10, 5, 25, false);
        addVerticalDeadEnd(walls, 13, 15, 8, 24, true);

        // A perpendicular pair makes wall-following choices locally attractive
        // but globally wrong for targets on the far side of each cap.
        addHorizontalDeadEnd(walls, 4, 11, 13, 15, true);

        cheeseMines[index(3, 3)] = true;
        cheeseMines[index(28, 28)] = true;

        RobotInfo[] kings = {
                new RobotInfo(1, Team.A, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, new MapLocation(3, 3),
                        Direction.NORTHEAST, 0, 0, null),
                new RobotInfo(2, Team.B, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, new MapLocation(28, 28),
                        Direction.SOUTHWEST, 1, 0, null)
        };

        LiveMap map = new LiveMap(
                WIDTH,
                HEIGHT,
                new MapLocation(0, 0),
                782,
                2000,
                "BugNavAdversarial",
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

        int wallCount = 0;
        for (boolean wall : walls) {
            if (wall) wallCount++;
        }
        System.out.printf("Wrote %s (%d walls, %.1f%% of map)%n",
                new File(outputDirectory, "BugNavAdversarial.map26"),
                wallCount,
                100.0 * wallCount / walls.length);
    }

    private static void addVerticalDeadEnd(
            boolean[] walls,
            int leftX,
            int rightX,
            int bottomY,
            int topY,
            boolean capAtTop) {
        for (int y = bottomY; y <= topY; y++) {
            setSymmetric(walls, leftX, y);
            setSymmetric(walls, rightX, y);
        }
        int capY = capAtTop ? topY : bottomY;
        for (int x = leftX; x <= rightX; x++) {
            setSymmetric(walls, x, capY);
        }
    }

    private static void addHorizontalDeadEnd(
            boolean[] walls,
            int leftX,
            int rightX,
            int bottomY,
            int topY,
            boolean capAtLeft) {
        for (int x = leftX; x <= rightX; x++) {
            setSymmetric(walls, x, bottomY);
            setSymmetric(walls, x, topY);
        }
        int capX = capAtLeft ? leftX : rightX;
        for (int y = bottomY; y <= topY; y++) {
            setSymmetric(walls, capX, y);
        }
    }

    private static void setSymmetric(boolean[] values, int x, int y) {
        values[index(x, y)] = true;
        values[index(WIDTH - 1 - x, HEIGHT - 1 - y)] = true;
    }

    private static int index(int x, int y) {
        return x + y * WIDTH;
    }
}
