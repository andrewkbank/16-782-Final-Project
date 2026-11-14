package lrta;

import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LearningRealTimeAStarTest {
    @Test
    public void searchResetClearsStringMapWithoutErasingLearning() throws Exception {
        LearningRealTimeAStar planner = planner();
        StringBuilder map = (StringBuilder) field(planner, "nodeByState");
        StringBuilder learned = (StringBuilder) field(planner, "adaptiveHeuristic");
        learned.setCharAt(0, (char) 24);
        Method begin = planner.getClass().getDeclaredMethod("beginSearch");
        begin.setAccessible(true);
        for (int trial = 0; trial < 3; trial++) {
            map.setCharAt(0, (char) 1);
            map.setCharAt(63, (char) 257); // triggers UTF-16 storage in the JDK
            begin.invoke(planner);
            assertEquals(64, map.length());
            for (int i = 0; i < map.length(); i++) assertEquals(0, map.charAt(i));
            assertEquals(23, learned.charAt(0) - 1);
        }
    }

    @Test
    public void stringQueueMatchesReferenceUnderInsertDecreaseAndPop() throws Exception {
        LearningRealTimeAStar planner = planner();
        long[] priority = (long[]) field(planner, "priority");
        Method push = planner.getClass().getDeclaredMethod("push", int.class);
        Method pop = planner.getClass().getDeclaredMethod("pop");
        Method decrease = planner.getClass().getDeclaredMethod("decreaseKey", int.class);
        push.setAccessible(true);
        pop.setAccessible(true);
        decrease.setAccessible(true);
        boolean[] active = new boolean[priority.length];
        Random random = new Random(260782);
        int size = 0;
        // Include NUL and the Latin-1/UTF-16 boundary explicitly. These are
        // single code units, not delimiter-encoded strings or Unicode points.
        for (int node : new int[]{0, 255, 256}) {
            priority[node] = 100_000L + node;
            push.invoke(planner, node);
            active[node] = true;
            size++;
        }
        for (int trial = 0; trial < 2000; trial++) {
            int node = random.nextInt(active.length);
            if (!active[node]) {
                priority[node] = 100_000L + random.nextInt(1000) * 1024L + node;
                push.invoke(planner, node);
                active[node] = true;
                size++;
            } else {
                priority[node] -= (1 + random.nextInt(100)) * 1024L;
                decrease.invoke(planner, node);
            }
            if (random.nextBoolean()) {
                int expected = -1;
                for (int i = 0; i < active.length; i++) {
                    if (active[i] && (expected < 0 || priority[i] < priority[expected])) expected = i;
                }
                assertEquals(expected, pop.invoke(planner));
                active[expected] = false;
                size--;
            }
            assertEquals(size, ((StringBuilder) field(planner, "open")).length());
        }
        long previous = Long.MIN_VALUE;
        while (size-- > 0) {
            int node = (int) pop.invoke(planner);
            assertTrue(active[node]);
            assertTrue(priority[node] >= previous);
            previous = priority[node];
            active[node] = false;
        }
        assertEquals(0, ((StringBuilder) field(planner, "open")).length());
    }

    private static LearningRealTimeAStar planner() {
        RobotController rc = (RobotController) Proxy.newProxyInstance(
                RobotController.class.getClassLoader(), new Class<?>[]{RobotController.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMapWidth", "getMapHeight" -> 8;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        LearningRealTimeAStar planner = new LearningRealTimeAStar(rc);
        planner.reset(new MapLocation(7, 7));
        return planner;
    }

    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    private static void set(Object object, String name, int value) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(object, value);
    }

    private static void learn(LearningRealTimeAStar planner) throws Exception {
        Method method = planner.getClass().getDeclaredMethod("updateHeuristics");
        method.setAccessible(true);
        method.invoke(planner);
    }

    @Test
    public void localLearningMatchesIndependentShortestPaths() throws Exception {
        // Random connected local graphs, with cycles and multiple frontier
        // values. Floyd-Warshall supplies an independent distance oracle.
        Random random = new Random(782);
        for (int trial = 0; trial < 100; trial++) {
            LearningRealTimeAStar planner = planner();
            int count = 2 + random.nextInt(6);
            int size = count + 2;
            int[] state = (int[]) field(planner, "state");
            int[] expanded = (int[]) field(planner, "expanded");
            int[] edges = (int[]) field(planner, "edges");
            byte[] status = (byte[]) field(planner, "status");
            long[] priority = (long[]) field(planner, "priority");
            StringBuilder learned = (StringBuilder) field(planner, "adaptiveHeuristic");
            Arrays.fill(edges, -1);
            int[][] distance = new int[size][size];
            int[] degree = new int[size];
            for (int i = 0; i < size; i++) {
                Arrays.fill(distance[i], 1000);
                distance[i][i] = 0;
                state[i] = i;
                status[i] = (byte) (i < count ? 2 : 1);
                priority[i] = (long) (i < count ? 0 : random.nextInt(10)) << 12;
                if (i < count) expanded[i] = i;
            }
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (j == i + 1 || random.nextBoolean()) {
                        distance[i][j] = distance[j][i] = 1;
                        edges[i * 8 + degree[i]++] = j;
                        edges[j * 8 + degree[j]++] = i;
                    }
                }
            }
            // Paths terminate at the first frontier, so only expanded nodes
            // may be intermediate vertices in the independent oracle.
            for (int k = 0; k < count; k++) {
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        distance[i][j] = Math.min(distance[i][j], distance[i][k] + distance[k][j]);
                    }
                }
            }
            set(planner, "expandedCount", count);
            learn(planner);
            for (int i = 0; i < count; i++) {
                int expected = Math.min(distance[i][count] + (int) (priority[count] >> 12),
                        distance[i][count + 1] + (int) (priority[count + 1] >> 12));
                assertEquals("trial " + trial + " node " + i, expected, learned.charAt(i) - 1);
            }
        }
    }

    @Test
    public void oneExpansionIsBellmanBackupAndResetInvalidatesLearning() throws Exception {
        LearningRealTimeAStar planner = planner();
        int[] edges = (int[]) field(planner, "edges");
        Arrays.fill(edges, -1);
        edges[0] = 1;
        edges[1] = 2;
        byte[] status = (byte[]) field(planner, "status");
        status[0] = 2;
        status[1] = status[2] = 1;
        long[] priority = (long[]) field(planner, "priority");
        priority[1] = 20L << 12;
        priority[2] = 24L << 12;
        set(planner, "expandedCount", 1);
        learn(planner);
        Method heuristic = planner.getClass().getDeclaredMethod("heuristic", int.class);
        heuristic.setAccessible(true);
        assertEquals(21, heuristic.invoke(planner, 0));
        // Check the largest h supported by the packed priority key, including
        // storage inflation beyond Latin-1 and a subsequent target reset.
        ((StringBuilder) field(planner, "adaptiveHeuristic")).setCharAt(0, (char) 4096);
        assertEquals(4095, heuristic.invoke(planner, 0));
        planner.reset(new MapLocation(1, 1));
        assertEquals(1, heuristic.invoke(planner, 0));
        assertEquals(0, heuristic.invoke(planner, 9)); // goal h=0 is a valid cached entry
        assertEquals(1, ((StringBuilder) field(planner, "adaptiveHeuristic")).charAt(9));
    }
}
