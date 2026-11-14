package maptools;

import battlecode.common.*;
import battlecode.world.*;
import trafficbench.TrafficTrial;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Chambers joined by an eight-cell corridor, with controlled passing space. */
public final class GenerateTrafficMapSuite {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]), report = new File(args[1]);
        dir.mkdirs(); report.mkdirs();
        int w = TrafficTrial.WIDTH, h = TrafficTrial.HEIGHT;
        String[] names = {"Traffic01_SingleLane", "Traffic02_PassingBays", "Traffic03_Width3", "Traffic04_Width5"};
        StringBuilder manifest = new StringBuilder(), json = new StringBuilder("[\n");
        BufferedImage picture = new BufferedImage(1600, 340, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1600, 340);
        for (int layout = 0; layout < names.length; layout++) {
            String name = names[layout];
            boolean[] walls = new boolean[w * h], mines = new boolean[w * h];
            int half = layout < 2 ? 0 : layout - 1;
            for (int x = 20; x <= 27; x++) for (int y = 0; y < h; y++) {
                boolean bay = layout == 1 && (x == 22 || x == 25) && Math.abs(y - 16) <= 1;
                walls[y * w + x] = Math.abs(y - 16) > half && !bay;
            }
            RobotInfo[] kings = new RobotInfo[2];
            for (int side = 0; side < 2; side++) {
                MapLocation k = TrafficTrial.king(side == 0);
                mines[k.y * w + k.x] = true;
                kings[side] = new RobotInfo(side + 1, side == 0 ? Team.A : Team.B,
                    UnitType.RAT_KING, UnitType.RAT_KING.health, k,
                    side == 0 ? Direction.EAST : Direction.WEST, side, 0, null);
            }
            LiveMap map = new LiveMap(w, h, new MapLocation(0, 0), 6147, TrafficTrial.ROUNDS,
                name, MapSymmetry.ROTATIONAL, walls, new boolean[w * h], mines,
                new int[w * h], new ArrayList<>(), new ArrayList<>(), kings);
            map.assertIsValid();
            boolean[] blocked = walls.clone();
            for (RobotInfo k : kings) for (MapLocation p : k.getType().getAllTypeLocations(k.getLocation())) blocked[p.y * w + p.x] = true;
            int[] distances = bfs(blocked, w, h, TrafficTrial.spawn(true, 0));
            for (int c = 0; c < blocked.length; c++) if (!blocked[c] && distances[c] < 0) throw new IllegalStateException("Disconnected " + name);
            GameMapIO.writeMap(map, dir);
            manifest.append(name).append('\n');
            if (layout > 0) json.append(",\n");
            json.append("{\"map\":\"").append(name).append("\",\"width\":48,\"height\":33,\"rounds\":600,\"release\":25,\"blocked_rows\":[");
            for (int y = 0; y < h; y++) {
                if (y > 0) json.append(',');
                json.append('"');
                for (int x = 0; x < w; x++) json.append(blocked[y * w + x] ? '#' : '.');
                json.append('"');
            }
            json.append("],\"cases\":[");
            for (int mode = 0; mode < TrafficTrial.MODES.length; mode++) {
                if (mode > 0) json.append(',');
                json.append("{\"mode\":\"").append(TrafficTrial.MODES[mode]).append("\",\"agents\":[");
                boolean first = true;
                for (int side = 0; side < 2; side++) for (int rank = 0; rank < TrafficTrial.count(mode, side == 0); rank++) {
                    MapLocation start = TrafficTrial.spawn(side == 0, rank), target = TrafficTrial.goal(side == 0, rank);
                    int optimum = bfs(blocked, w, h, start)[target.y * w + target.x];
                    if (optimum < 0) throw new IllegalStateException("Unreachable traffic route");
                    if (!first) json.append(','); first = false;
                    json.append(String.format(Locale.ROOT,
                        "{\"side\":\"%s\",\"rank\":%d,\"start_x\":%d,\"start_y\":%d,\"target_x\":%d,\"target_y\":%d,\"bfs_steps\":%d}",
                        side == 0 ? "A" : "B", rank, start.x, start.y, target.x, target.y, optimum));
                }
                json.append("]}");
            }
            json.append("]}");
            int ox = layout * 400 + 8, oy = 35, scale = 8;
            g.setColor(Color.BLACK); g.drawString(name, ox, 20);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = y * w + x;
                g.setColor(walls[c] ? new Color(35, 42, 52) : blocked[c] ? Color.GRAY : new Color(238, 242, 246));
                g.fillRect(ox + x * scale, oy + (h - 1 - y) * scale, scale, scale);
            }
            for (int side = 0; side < 2; side++) {
                g.setColor(side == 0 ? new Color(20, 130, 220) : new Color(220, 100, 20));
                for (int rank = 0; rank < 8; rank++) {
                    MapLocation p = TrafficTrial.spawn(side == 0, rank);
                    g.fillOval(ox + p.x * scale + 1, oy + (h - 1 - p.y) * scale + 1, 6, 6);
                }
                MapLocation p = TrafficTrial.goal(side == 0, 0);
                g.drawOval(ox + p.x * scale, oy + (h - 1 - p.y) * scale, 8, 8);
            }
            g.setColor(Color.BLACK); g.drawString("Dots: staging slots; circles: rank-0 goals", ox, 320);
            System.out.println("Generated " + name);
        }
        g.dispose();
        Files.writeString(new File(dir, "traffic-suite-maps.txt").toPath(), manifest);
        Files.writeString(new File(dir, "traffic-scenarios.json").toPath(), json.append("\n]\n"));
        ImageIO.write(picture, "png", new File(report, "maps.png"));
    }
    private static int[] bfs(boolean[] blocked, int w, int h, MapLocation start) {
        int[] d = new int[w * h]; Arrays.fill(d, -1);
        int root = start.y * w + start.x;
        if (blocked[root]) throw new IllegalStateException("Blocked spawn");
        ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(root); d[root] = 0;
        while (!q.isEmpty()) {
            int c = q.removeFirst();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                int x = c % w + dx, y = c / w + dy;
                if (x < 0 || x >= w || y < 0 || y >= h) continue;
                int next = y * w + x;
                if (!blocked[next] && d[next] < 0) { d[next] = d[c] + 1; q.add(next); }
            }
        }
        return d;
    }
}
