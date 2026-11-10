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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Generates 40 reproducible, connected maps for path-planner benchmarking. */
public final class GeneratePathfindingMapSuite {
    public static final int MAP_COUNT = 40;
    private static final int KING_X = 3;
    private static final int KING_Y = 3;
    private static final double MAX_WALL_FRACTION = 0.16;
    private static final String[] FAMILIES = {
        "Staggered", "Rooms", "Rings", "Combs", "Spirals"
    };

    private GeneratePathfindingMapSuite() {}

    public static void main(String[] args) throws Exception {
        File outputDirectory = new File(args.length == 0 ? "maps" : args[0]);
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create " + outputDirectory);
        }

        Set<String> fingerprints = new HashSet<>();
        StringBuilder manifest = new StringBuilder();
        for (int mapNumber = 1; mapNumber <= MAP_COUNT; mapNumber++) {
            int family = (mapNumber - 1) % FAMILIES.length;
            int variant = (mapNumber - 1) / FAMILIES.length;
            int width = 24 + 2 * ((mapNumber * 3 + variant) % 8);
            int height = 24 + 2 * ((mapNumber * 5 + 2 * variant) % 8);
            String name = String.format("PathSuite%02d_%s", mapNumber, FAMILIES[family]);
            Random random = new Random(16_782L + mapNumber * 7_919L);
            Layout layout = new Layout(width, height);

            switch (family) {
                case 0 -> addStaggeredBarriers(layout, random, variant);
                case 1 -> addRooms(layout, random, variant);
                case 2 -> addRings(layout, random, variant);
                case 3 -> addCombs(layout, random, variant);
                case 4 -> addSpirals(layout, random, variant);
                default -> throw new AssertionError(family);
            }

            layout.clearProtectedZones();
            layout.limitWallDensity(MAX_WALL_FRACTION);
            layout.connectAllPassableCells();
            layout.clearProtectedZones();
            layout.assertConnected();

            String fingerprint = width + "x" + height + ":" + Arrays.toString(layout.walls);
            if (!fingerprints.add(fingerprint)) {
                throw new IllegalStateException("Duplicate wall layout: " + name);
            }

            boolean[] dirt = new boolean[width * height];
            boolean[] cheeseMines = new boolean[width * height];
            int[] cheese = new int[width * height];
            MapLocation kingA = new MapLocation(KING_X, KING_Y);
            MapLocation kingB = new MapLocation(width - 1 - KING_X, height - 1 - KING_Y);
            cheeseMines[layout.index(kingA.x, kingA.y)] = true;
            cheeseMines[layout.index(kingB.x, kingB.y)] = true;
            RobotInfo[] kings = {
                new RobotInfo(1, Team.A, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, kingA,
                        Direction.NORTHEAST, 0, 0, null),
                new RobotInfo(2, Team.B, UnitType.RAT_KING,
                        UnitType.RAT_KING.health, kingB,
                        Direction.SOUTHWEST, 1, 0, null)
            };

            LiveMap map = new LiveMap(
                    width,
                    height,
                    new MapLocation(0, 0),
                    6_147, // Known-good cheese sequence for 2,000-round survival.
                    2000,
                    name,
                    MapSymmetry.ROTATIONAL,
                    layout.walls,
                    dirt,
                    cheeseMines,
                    cheese,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    kings);
            map.assertIsValid();
            GameMapIO.writeMap(map, outputDirectory);

            int walls = layout.wallCount();
            manifest.append(name).append('\n');
            System.out.printf("Wrote %-25s %2dx%2d, %3d walls (%4.1f%%)%n",
                    name, width, height, walls, 100.0 * walls / (width * height));
        }

        File manifestFile = new File(outputDirectory, "path-suite-maps.txt");
        Files.writeString(manifestFile.toPath(), manifest.toString(), StandardCharsets.UTF_8);
        System.out.printf("Generated %d unique valid maps; manifest: %s%n",
                MAP_COUNT, manifestFile);
    }

    private static void addStaggeredBarriers(Layout layout, Random random, int variant) {
        for (int x = 7 + variant % 3; x < layout.width / 2; x += 5) {
            int gateA = 3 + random.nextInt(layout.height - 6);
            int gateB = 3 + random.nextInt(layout.height - 6);
            for (int y = 2; y < layout.height - 2; y++) {
                if (Math.abs(y - gateA) > 1 && Math.abs(y - gateB) > 1) {
                    layout.setSymmetric(x, y);
                }
            }
        }
        for (int y = 8 + variant % 2; y < layout.height / 2; y += 7) {
            int gate = 4 + random.nextInt(Math.max(1, layout.width / 2 - 7));
            for (int x = 5; x < layout.width / 2 + 2; x++) {
                if (Math.abs(x - gate) > 1) {
                    layout.setSymmetric(x, y);
                }
            }
        }
    }

    private static void addRooms(Layout layout, Random random, int variant) {
        int spacing = 6 + variant % 2;
        for (int x = spacing; x < layout.width; x += spacing) {
            for (int y = 2; y < layout.height - 2; y++) {
                int band = y / spacing;
                int door = 2 + Math.floorMod(x * 3 + band * 5 + variant, spacing - 2);
                if (y % spacing != door) {
                    layout.setSymmetric(x, y);
                }
            }
        }
        for (int y = spacing; y < layout.height; y += spacing) {
            for (int x = 2; x < layout.width - 2; x++) {
                int band = x / spacing;
                int door = 2 + Math.floorMod(y * 5 + band * 3 + random.nextInt(3), spacing - 2);
                if (x % spacing != door) {
                    layout.setSymmetric(x, y);
                }
            }
        }
    }

    private static void addRings(Layout layout, Random random, int variant) {
        int maxInset = Math.min(layout.width, layout.height) / 2 - 3;
        for (int inset = 6 + variant % 2; inset <= maxInset; inset += 4) {
            int left = inset;
            int right = layout.width - 1 - inset;
            int bottom = inset;
            int top = layout.height - 1 - inset;
            if (right - left < 5 || top - bottom < 5) {
                break;
            }
            for (int x = left; x <= right; x++) {
                layout.setSymmetric(x, bottom);
                layout.setSymmetric(x, top);
            }
            for (int y = bottom; y <= top; y++) {
                layout.setSymmetric(left, y);
                layout.setSymmetric(right, y);
            }
            int horizontalGate = left + 2 + random.nextInt(right - left - 3);
            int verticalGate = bottom + 2 + random.nextInt(top - bottom - 3);
            layout.clearSymmetric(horizontalGate, bottom);
            layout.clearSymmetric(horizontalGate + 1, bottom);
            layout.clearSymmetric(left, verticalGate);
            layout.clearSymmetric(left, verticalGate + 1);
        }
    }

    private static void addCombs(Layout layout, Random random, int variant) {
        int backboneX = layout.width / 2 - 3 - variant % 2;
        for (int y = 4; y < layout.height - 4; y++) {
            layout.setSymmetric(backboneX, y);
        }
        for (int y = 7 + variant % 3; y < layout.height - 5; y += 4) {
            int length = 5 + random.nextInt(Math.max(1, layout.width / 2 - 8));
            boolean left = ((y / 4) + variant) % 2 == 0;
            for (int offset = 0; offset <= length; offset++) {
                int x = left ? backboneX - offset : backboneX + offset;
                layout.setSymmetric(x, y);
            }
        }
        int gate = 5 + random.nextInt(Math.max(1, layout.height - 10));
        layout.clearSymmetric(backboneX, gate);
        layout.clearSymmetric(backboneX, gate + 1);
    }

    private static void addSpirals(Layout layout, Random random, int variant) {
        int layers = 2 + variant % 3;
        for (int layer = 0; layer < layers; layer++) {
            int left = 6 + layer * 4;
            int right = layout.width - 7 - layer * 4;
            int bottom = 5 + layer * 4;
            int top = layout.height - 6 - layer * 4;
            if (right - left < 6 || top - bottom < 6) {
                break;
            }
            int topGap = right - 2 - random.nextInt(2);
            int sideGap = bottom + 2 + random.nextInt(2);
            for (int x = left; x <= right; x++) {
                if (x != topGap && x != topGap + 1) {
                    layout.setSymmetric(x, top);
                }
            }
            for (int y = bottom; y <= top; y++) {
                if (y != sideGap && y != sideGap + 1) {
                    layout.setSymmetric(left, y);
                }
            }
            for (int x = left; x < right - 3; x++) {
                layout.setSymmetric(x, bottom);
            }
            for (int y = bottom + 4; y <= top; y++) {
                layout.setSymmetric(right, y);
            }
        }
    }

    private static final class Layout {
        final int width;
        final int height;
        final boolean[] walls;

        Layout(int width, int height) {
            this.width = width;
            this.height = height;
            walls = new boolean[width * height];
        }

        int index(int x, int y) {
            return x + y * width;
        }

        void setSymmetric(int x, int y) {
            set(x, y, true);
            set(width - 1 - x, height - 1 - y, true);
        }

        void clearSymmetric(int x, int y) {
            set(x, y, false);
            set(width - 1 - x, height - 1 - y, false);
        }

        private void set(int x, int y, boolean value) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                walls[index(x, y)] = value;
            }
        }

        void clearProtectedZones() {
            clearSquare(KING_X, KING_Y, 4);
            clearSquare(width - 1 - KING_X, height - 1 - KING_Y, 4);
        }

        private void clearSquare(int centerX, int centerY, int radius) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int y = centerY - radius; y <= centerY + radius; y++) {
                    clearSymmetric(x, y);
                }
            }
        }

        int wallCount() {
            int count = 0;
            for (boolean wall : walls) {
                if (wall) count++;
            }
            return count;
        }

        void limitWallDensity(double maximum) {
            int target = (int) Math.floor(walls.length * maximum);
            for (int phase = 0; phase < 5 && wallCount() > target; phase++) {
                for (int y = 0; wallCount() > target && y < height; y++) {
                    for (int x = 0; wallCount() > target && x < width; x++) {
                        if ((x * 31 + y * 17) % 5 == phase) {
                            clearSymmetric(x, y);
                        }
                    }
                }
            }
        }

        void connectAllPassableCells() {
            for (int repair = 0; repair < walls.length; repair++) {
                boolean[] reached = floodFrom(KING_X, KING_Y);
                int disconnected = -1;
                for (int i = 0; i < walls.length; i++) {
                    if (!walls[i] && !reached[i]) {
                        disconnected = i;
                        break;
                    }
                }
                if (disconnected < 0) {
                    return;
                }
                int x = disconnected % width;
                int y = disconnected / width;
                while (x != KING_X) {
                    clearSymmetric(x, y);
                    x += Integer.compare(KING_X, x);
                }
                while (y != KING_Y) {
                    clearSymmetric(x, y);
                    y += Integer.compare(KING_Y, y);
                }
                clearSymmetric(x, y);
            }
            throw new IllegalStateException("Could not connect passable cells");
        }

        void assertConnected() {
            boolean[] reached = floodFrom(KING_X, KING_Y);
            for (int i = 0; i < walls.length; i++) {
                if (!walls[i] && !reached[i]) {
                    throw new IllegalStateException("Disconnected passable tile " + i);
                }
            }
        }

        private boolean[] floodFrom(int startX, int startY) {
            boolean[] reached = new boolean[walls.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int start = index(startX, startY);
            if (walls[start]) {
                return reached;
            }
            reached[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int x = current % width;
                int y = current / width;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                        int next = index(nx, ny);
                        if (!walls[next] && !reached[next]) {
                            reached[next] = true;
                            queue.addLast(next);
                        }
                    }
                }
            }
            return reached;
        }
    }
}
