package maptools;

import battlecode.common.*;
import battlecode.world.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Unrepaired, rotationally symmetric concave obstacles and open controls. */
public final class GenerateConcaveMapSuite {
    private static final String[] FAMILIES = {"WideU", "Bottle", "CombBays", "WideU", "Bottle", "CombBays", "Open", "Open"};
    private static final int[] SIZES = {40, 40, 40, 56, 56, 56, 40, 56};

    public static void main(String[] args) throws Exception {
        File directory = new File(args.length > 0 ? args[0] : "maps");
        File report = new File(args.length > 1 ? args[1] : "reports/concavity");
        directory.mkdirs();
        report.mkdirs();
        StringBuilder manifest = new StringBuilder();
        StringBuilder metadata = new StringBuilder("[\n");
        BufferedImage overview = new BufferedImage(1920, 1040, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = overview.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, overview.getWidth(), overview.getHeight());
        for (int i = 0; i < FAMILIES.length; i++) {
            int n = SIZES[i], left = 4, right = n / 2 - 4, middle = (left + right) / 2;
            int top = n - 12, chamberTop = n / 2;
            String family = FAMILIES[i];
            String name = String.format("Concave%02d_%s%d", i + 1, family, n);
            boolean[] walls = new boolean[n * n];
            if (!family.equals("Open")) {
                for (int x = left; x <= right; x++) wall(walls, n, x, 4);
                int sideTop = family.equals("Bottle") ? chamberTop : top;
                for (int y = 4; y <= sideTop; y++) {
                    wall(walls, n, left, y);
                    wall(walls, n, right, y);
                }
                if (family.equals("Bottle")) {
                    for (int x = left; x <= right; x++) {
                        if (Math.abs(x - middle) >= 2) wall(walls, n, x, chamberTop);
                    }
                    for (int y = chamberTop; y <= top; y++) {
                        wall(walls, n, middle - 2, y);
                        wall(walls, n, middle + 2, y);
                    }
                } else if (family.equals("CombBays")) {
                    for (int y = 4; y <= top; y++) wall(walls, n, middle, y);
                }
            }
            int kingX = family.equals("CombBays") ? (left + middle) / 2 : middle;
            MapLocation kingA = new MapLocation(kingX, 8);
            MapLocation kingB = new MapLocation(n - 1 - kingX, n - 9);
            MapLocation start = new MapLocation(kingX, 6);
            MapLocation goal = new MapLocation(kingX, 2);
            boolean[] mines = new boolean[n * n];
            mines[index(n, kingA.x, kingA.y)] = mines[index(n, kingB.x, kingB.y)] = true;
            // Supplement mine output for stationary king survival. Map serialization
            // uses signed bytes, so each pile must be <= 127. Kings collect these
            // nine piles directly into team reserves; moving rats carry no cheese.
            int[] cheese = new int[n * n];
            for (MapLocation king : new MapLocation[]{kingA, kingB}) {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    cheese[index(n, king.x + dx, king.y + dy)] = 127;
                }
            }
            RobotInfo[] kings = {
                new RobotInfo(1, Team.A, UnitType.RAT_KING, UnitType.RAT_KING.health, kingA, Direction.NORTHEAST, 0, 0, null),
                new RobotInfo(2, Team.B, UnitType.RAT_KING, UnitType.RAT_KING.health, kingB, Direction.SOUTHWEST, 1, 0, null)
            };
            LiveMap map = new LiveMap(n, n, new MapLocation(0, 0), 6147, 2000, name,
                    MapSymmetry.ROTATIONAL, walls, new boolean[n * n], mines, cheese,
                    new ArrayList<>(), new ArrayList<>(), kings);
            map.assertIsValid();
            boolean[] blocked = walls.clone();
            for (MapLocation king : new MapLocation[]{kingA, kingB}) {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    int cell = index(n, king.x + dx, king.y + dy);
                    if (walls[cell]) throw new IllegalStateException("Wall in king footprint: " + name);
                    blocked[cell] = true;
                }
            }
            int[] parent = bfs(blocked, n, start);
            for (int cell = 0; cell < blocked.length; cell++) {
                if (!blocked[cell] && parent[cell] < 0) throw new IllegalStateException("Disconnected cell in " + name);
            }
            int startCell = index(n, start.x, start.y), goalCell = index(n, goal.x, goal.y), optimum = 0;
            for (int cell = goalCell; cell != startCell; cell = parent[cell]) optimum++;
            int area = 0, wallCount = 0;
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                if (walls[index(n, x, y)]) wallCount++;
                boolean inside = x > left && x < (family.equals("CombBays") ? middle : right) && y > 4 && y <= top;
                if (family.equals("Bottle")) inside &= y < chamberTop || Math.abs(x - middle) < 2;
                if (!family.equals("Open") && inside && !blocked[index(n, x, y)]) area++;
            }
            GameMapIO.writeMap(map, directory);
            LiveMap reloaded = GameMapIO.loadMap(name, directory, true);
            if (!Arrays.equals(cheese, reloaded.getCheeseArray())) {
                throw new IllegalStateException("Cheese did not survive map serialization: " + name);
            }
            manifest.append(name).append('\n');
            if (i > 0) metadata.append(",\n");
            metadata.append(String.format(java.util.Locale.ROOT,
                    "  {\"map\":\"%s\",\"family\":\"%s\",\"size\":%d,\"start_x\":%d,\"start_y\":6,\"target_x\":%d,\"target_y\":2,\"escape_y\":%s,\"trap_free_cells\":%d,\"bfs_steps\":%d,\"walls\":%d}",
                    name, family, n, kingX, kingX, family.equals("Open") ? "null" : Integer.toString(top), area, optimum, wallCount));
            // Put the three small/large trap families in aligned columns; controls in the fourth.
            int column = family.equals("Open") ? 3 : i % 3;
            int row = n == 40 ? 0 : 1;
            draw(graphics, column * 480, row * 520, name, walls, blocked, n, start, goal, parent, optimum);
            System.out.printf("%s: start=%s goal=%s BFS=%d trap area=%d walls=%d%n", name, start, goal, optimum, area, wallCount);
        }
        graphics.dispose();
        metadata.append("\n]\n");
        Files.writeString(new File(directory, "concave-suite-maps.txt").toPath(), manifest);
        Files.writeString(new File(directory, "concave-scenarios.json").toPath(), metadata);
        ImageIO.write(overview, "png", new File(report, "maps.png"));
    }

    private static int index(int n, int x, int y) { return y * n + x; }
    private static void wall(boolean[] walls, int n, int x, int y) {
        walls[index(n, x, y)] = walls[index(n, n - 1 - x, n - 1 - y)] = true;
    }
    private static int[] bfs(boolean[] blocked, int n, MapLocation start) {
        int[] parent = new int[n * n];
        Arrays.fill(parent, -1);
        int first = index(n, start.x, start.y);
        if (blocked[first]) throw new IllegalStateException("Blocked start");
        parent[first] = first;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(first);
        while (!queue.isEmpty()) {
            int cell = queue.removeFirst(), x = cell % n, y = cell / n;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                int next = index(n, nx, ny);
                if (!blocked[next] && parent[next] < 0) {
                    parent[next] = cell;
                    queue.addLast(next);
                }
            }
        }
        return parent;
    }
    private static void draw(Graphics2D g, int ox, int oy, String name, boolean[] walls,
            boolean[] blocked, int n, MapLocation start, MapLocation goal, int[] parent, int optimum) {
        int scale = 8, x0 = ox + 12, y0 = oy + 42;
        g.setColor(Color.BLACK);
        g.drawString(name + "  BFS=" + optimum, ox + 12, oy + 20);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int cell = index(n, x, y);
            g.setColor(walls[cell] ? new Color(35, 42, 52) : blocked[cell] ? Color.GRAY : new Color(238, 242, 246));
            g.fillRect(x0 + x * scale, y0 + (n - 1 - y) * scale, scale, scale);
        }
        g.setColor(new Color(30, 130, 215));
        g.setStroke(new BasicStroke(2));
        int first = index(n, start.x, start.y);
        for (int cell = index(n, goal.x, goal.y); cell != first; cell = parent[cell]) {
            int p = parent[cell];
            g.drawLine(x0 + (cell % n) * scale + 4, y0 + (n - 1 - cell / n) * scale + 4,
                    x0 + (p % n) * scale + 4, y0 + (n - 1 - p / n) * scale + 4);
        }
        for (MapLocation point : new MapLocation[]{start, goal}) {
            g.setColor(point == start ? new Color(0, 160, 70) : new Color(220, 45, 45));
            g.fillOval(x0 + point.x * scale - 1, y0 + (n - 1 - point.y) * scale - 1, 10, 10);
        }
        g.setColor(Color.BLACK);
        g.drawString("Green: start   Red: goal   Blue: BFS   Gray: kings", ox + 12, oy + 507);
    }
}
