package pathanalysis;

import battlecode.common.Direction;
import battlecode.common.MapLocation;
import battlecode.common.RobotInfo;
import battlecode.schema.Action;
import battlecode.schema.Event;
import battlecode.schema.EventWrapper;
import battlecode.schema.GameWrapper;
import battlecode.schema.IndicatorStringAction;
import battlecode.schema.Round;
import battlecode.schema.Turn;
import battlecode.world.GameMapIO;
import battlecode.world.LiveMap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Extracts baby-rat paths from an engine replay and compares each target run
 * with an optimal static-map path. This runs outside the robot sandbox, so path
 * collection and BFS consume no bot bytecodes.
 */
public final class PathPerformanceAnalyzer {
    private static final Pattern TARGET_PATTERN =
            Pattern.compile("^PATH_TARGET:(-?\\d+),(-?\\d+)$");

    private PathPerformanceAnalyzer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: PathPerformanceAnalyzer <replay> <map-dir> <map-name> <output-dir>");
        }

        Path replayPath = Path.of(args[0]);
        File mapDirectory = new File(args[1]);
        String mapName = args[2];
        Path outputDirectory = Path.of(args[3]);
        Files.createDirectories(outputDirectory);

        LiveMap map = GameMapIO.loadMap(mapName, mapDirectory, true);
        Analysis analysis = readReplay(replayPath, map);
        List<Segment> segments = buildSegments(analysis.observations, map);

        Path tracePath = outputDirectory.resolve("path-trace.csv");
        Path summaryPath = outputDirectory.resolve("path-summary.csv");
        writeTrace(tracePath, analysis.observations);
        writeSummary(summaryPath, segments);
        printSummary(replayPath, tracePath, summaryPath, analysis.observations, segments);
    }

    private static Analysis readReplay(Path replayPath, LiveMap map) throws IOException {
        byte[] replayBytes;
        try (InputStream file = Files.newInputStream(replayPath);
             GZIPInputStream gzip = new GZIPInputStream(file)) {
            replayBytes = gzip.readAllBytes();
        }
        ByteBuffer buffer = ByteBuffer.wrap(replayBytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        GameWrapper game = GameWrapper.getRootAsGameWrapper(buffer);

        Set<Integer> initialRobotIds = new HashSet<>();
        for (RobotInfo robot : map.getInitialBodies()) {
            initialRobotIds.add(robot.getID());
        }

        Map<Integer, List<Observation>> observations = new HashMap<>();
        for (int eventIndex = 0; eventIndex < game.eventsLength(); eventIndex++) {
            EventWrapper event = game.events(eventIndex);
            if (event.eType() != Event.Round) {
                continue;
            }

            Round round = (Round) event.e(new Round());
            for (int turnIndex = 0; turnIndex < round.turnsLength(); turnIndex++) {
                Turn turn = round.turns(turnIndex);
                if (initialRobotIds.contains(turn.robotId())) {
                    continue;
                }

                MapLocation announcedTarget = readTargetAnnouncement(turn);
                Observation observation = new Observation(
                        round.roundId(),
                        turn.robotId(),
                        new MapLocation(turn.x(), turn.y()),
                        turn.dir(),
                        turn.moveCooldown(),
                        turn.turningCooldown(),
                        turn.actionCooldown(),
                        turn.bytecodesUsed(),
                        announcedTarget);
                observations.computeIfAbsent(turn.robotId(), unused -> new ArrayList<>())
                        .add(observation);
            }
        }

        for (List<Observation> robotObservations : observations.values()) {
            robotObservations.sort(Comparator.comparingInt(Observation::round));
        }
        return new Analysis(observations);
    }

    private static MapLocation readTargetAnnouncement(Turn turn) {
        for (int actionIndex = 0; actionIndex < turn.actionsLength(); actionIndex++) {
            if (turn.actionsType(actionIndex) != Action.IndicatorStringAction) {
                continue;
            }
            IndicatorStringAction action = (IndicatorStringAction) turn.actions(
                    new IndicatorStringAction(), actionIndex);
            Matcher matcher = TARGET_PATTERN.matcher(action.value());
            if (matcher.matches()) {
                return new MapLocation(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)));
            }
        }
        return null;
    }

    private static List<Segment> buildSegments(
            Map<Integer, List<Observation>> byRobot,
            LiveMap map) {
        boolean[] blocked = staticBlockedTiles(map);
        List<Segment> result = new ArrayList<>();

        for (Map.Entry<Integer, List<Observation>> entry : byRobot.entrySet()) {
            Segment current = null;
            Observation previous = null;

            for (Observation observation : entry.getValue()) {
                if (observation.announcedTarget() != null) {
                    if (current != null) {
                        current.finishIncomplete(previous.round(), "retargeted");
                        result.add(current);
                    }
                    int optimal = shortestPathLength(
                            map,
                            blocked,
                            observation.location(),
                            observation.announcedTarget());
                    current = new Segment(observation, optimal);
                    previous = observation;
                    continue;
                }

                if (current != null && previous != null && !current.reached()) {
                    current.observe(previous, observation);
                    if (observation.location().equals(current.target())) {
                        current.finishReached(observation.round());
                    }
                }
                previous = observation;
            }

            if (current != null) {
                current.finishIncomplete(previous.round(), current.reached() ? "reached" : "match-ended");
                result.add(current);
            }
        }

        result.sort(Comparator.comparingInt(Segment::robotId)
                .thenComparingInt(Segment::assignmentRound));
        return result;
    }

    private static boolean[] staticBlockedTiles(LiveMap map) {
        boolean[] blocked = new boolean[map.getWidth() * map.getHeight()];
        boolean[] walls = map.getWallArray();
        boolean[] dirt = map.getDirtArray();
        for (int i = 0; i < blocked.length; i++) {
            blocked[i] = walls[i] || dirt[i];
        }

        // Initial kings and cats are static obstacles for this benchmark.
        for (RobotInfo robot : map.getInitialBodies()) {
            for (MapLocation occupied : robot.getType().getAllTypeLocations(robot.getLocation())) {
                if (map.onTheMap(occupied)) {
                    blocked[map.locationToIndex(occupied)] = true;
                }
            }
        }
        return blocked;
    }

    private static int shortestPathLength(
            LiveMap map,
            boolean[] blocked,
            MapLocation start,
            MapLocation target) {
        if (!map.onTheMap(start) || !map.onTheMap(target)) {
            return -1;
        }
        int startIndex = map.locationToIndex(start);
        int targetIndex = map.locationToIndex(target);
        if (blocked[targetIndex] && startIndex != targetIndex) {
            return -1;
        }

        int[] distance = new int[blocked.length];
        java.util.Arrays.fill(distance, -1);
        ArrayDeque<MapLocation> queue = new ArrayDeque<>();
        distance[startIndex] = 0;
        queue.add(start);

        while (!queue.isEmpty()) {
            MapLocation current = queue.removeFirst();
            int currentDistance = distance[map.locationToIndex(current)];
            if (current.equals(target)) {
                return currentDistance;
            }

            for (Direction direction : Direction.allDirections()) {
                if (direction == Direction.CENTER) {
                    continue;
                }
                MapLocation next = current.add(direction);
                if (!map.onTheMap(next)) {
                    continue;
                }
                int nextIndex = map.locationToIndex(next);
                if (blocked[nextIndex] || distance[nextIndex] >= 0) {
                    continue;
                }
                distance[nextIndex] = currentDistance + 1;
                queue.addLast(next);
            }
        }
        return -1;
    }

    private static void writeTrace(
            Path output,
            Map<Integer, List<Observation>> byRobot) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("robot_id,round,x,y,direction,move_cooldown,turning_cooldown,"
                    + "action_cooldown,bytecodes_used,target_x,target_y,segment_id\n");
            List<Integer> robotIds = new ArrayList<>(byRobot.keySet());
            robotIds.sort(Integer::compareTo);
            for (int robotId : robotIds) {
                MapLocation target = null;
                int segmentId = 0;
                for (Observation observation : byRobot.get(robotId)) {
                    if (observation.announcedTarget() != null) {
                        target = observation.announcedTarget();
                        segmentId++;
                    }
                    writer.write(String.format(Locale.ROOT,
                            "%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%d%n",
                            robotId,
                            observation.round(),
                            observation.location().x,
                            observation.location().y,
                            observation.direction(),
                            observation.moveCooldown(),
                            observation.turningCooldown(),
                            observation.actionCooldown(),
                            observation.bytecodesUsed(),
                            target == null ? "" : Integer.toString(target.x),
                            target == null ? "" : Integer.toString(target.y),
                            segmentId));
                }
            }
        }
    }

    private static void writeSummary(Path output, List<Segment> segments) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("robot_id,assignment_round,start_x,start_y,target_x,target_y,status,"
                    + "finish_round,actual_rounds,actual_steps,optimal_steps,optimal_rounds,"
                    + "step_detour,round_overhead,path_efficiency,observed_turns,"
                    + "total_bytecodes,mean_bytecodes_per_turn,max_bytecodes_in_turn,"
                    + "bytecodes_per_step\n");
            for (Segment segment : segments) {
                writer.write(segment.toCsv());
                writer.newLine();
            }
        }
    }

    private static void printSummary(
            Path replay,
            Path trace,
            Path summary,
            Map<Integer, List<Observation>> observations,
            List<Segment> segments) {
        long reached = segments.stream().filter(Segment::reached).count();
        double averageEfficiency = segments.stream()
                .filter(segment -> segment.reached() && segment.optimalSteps() >= 0
                        && segment.actualSteps() > 0)
                .mapToDouble(segment -> (double) segment.optimalSteps() / segment.actualSteps())
                .average()
                .orElse(Double.NaN);
        List<Segment> reachedSegments = segments.stream()
                .filter(Segment::reached)
                .toList();
        long reachedBytecodes = reachedSegments.stream()
                .mapToLong(Segment::totalBytecodes)
                .sum();
        long reachedTurns = reachedSegments.stream()
                .mapToLong(Segment::observedTurns)
                .sum();
        long reachedSteps = reachedSegments.stream()
                .mapToLong(Segment::actualSteps)
                .sum();
        int peakTurnBytecodes = reachedSegments.stream()
                .mapToInt(Segment::maxBytecodesInTurn)
                .max()
                .orElse(0);

        System.out.println("Analyzed replay: " + replay.toAbsolutePath());
        System.out.println("Baby rats tracked: " + observations.size());
        System.out.println("Target segments: " + segments.size() + " (reached: " + reached + ")");
        if (!Double.isNaN(averageEfficiency)) {
            System.out.printf(Locale.ROOT, "Mean reached-segment path efficiency: %.3f%n",
                    averageEfficiency);
        }
        if (!reachedSegments.isEmpty()) {
            System.out.printf(Locale.ROOT,
                    "Reached-segment bytecodes: %.1f/segment, %.1f/turn, %.1f/step, %d peak%n",
                    (double) reachedBytecodes / reachedSegments.size(),
                    (double) reachedBytecodes / reachedTurns,
                    (double) reachedBytecodes / reachedSteps,
                    peakTurnBytecodes);
        }
        System.out.println("Per-round trace: " + trace.toAbsolutePath());
        System.out.println("Segment summary: " + summary.toAbsolutePath());
    }

    private record Analysis(Map<Integer, List<Observation>> observations) {}

    private record Observation(
            int round,
            int robotId,
            MapLocation location,
            int direction,
            int moveCooldown,
            int turningCooldown,
            int actionCooldown,
            int bytecodesUsed,
            MapLocation announcedTarget) {}

    private static final class Segment {
        private final int robotId;
        private final int assignmentRound;
        private final MapLocation start;
        private final MapLocation target;
        private final int optimalSteps;
        private int actualSteps;
        private int finishRound;
        private boolean reached;
        private String status = "active";
        private int observedTurns;
        private long totalBytecodes;
        private int maxBytecodesInTurn;
        private int lastMovementRound;

        Segment(Observation assignment, int optimalSteps) {
            this.robotId = assignment.robotId();
            this.assignmentRound = assignment.round();
            this.start = assignment.location();
            this.target = assignment.announcedTarget();
            this.optimalSteps = optimalSteps;
            this.finishRound = assignmentRound;
            this.lastMovementRound = assignmentRound;
            addBytecodes(assignment.bytecodesUsed());
        }

        void observe(Observation previous, Observation current) {
            if (!previous.location().equals(current.location())) {
                actualSteps++;
                lastMovementRound = current.round();
            }
            finishRound = current.round();
            addBytecodes(current.bytecodesUsed());
        }

        private void addBytecodes(int bytecodes) {
            observedTurns++;
            totalBytecodes += bytecodes;
            maxBytecodesInTurn = Math.max(maxBytecodesInTurn, bytecodes);
        }

        void finishReached(int round) {
            reached = true;
            finishRound = round;
            status = "reached";
        }

        void finishIncomplete(int round, String newStatus) {
            if (reached) {
                return;
            }
            finishRound = Math.max(finishRound, round);
            if ("match-ended".equals(newStatus) && optimalSteps < 0) {
                status = "unreachable-stalled";
            } else if ("match-ended".equals(newStatus)
                    && round - lastMovementRound >= 50) {
                status = "reachable-stalled";
            } else {
                status = newStatus;
            }
        }

        String toCsv() {
            int actualRounds = finishRound - assignmentRound;
            String optimal = optimalSteps < 0 ? "" : Integer.toString(optimalSteps);
            String detour = reached && optimalSteps >= 0
                    ? Integer.toString(actualSteps - optimalSteps) : "";
            String overhead = reached && optimalSteps >= 0
                    ? Integer.toString(actualRounds - optimalSteps) : "";
            String efficiency = reached && optimalSteps >= 0
                    ? String.format(Locale.ROOT, "%.6f",
                            actualSteps == 0 ? 1.0 : (double) optimalSteps / actualSteps)
                    : "";
            String meanBytecodes = String.format(Locale.ROOT, "%.3f",
                    (double) totalBytecodes / observedTurns);
            String bytecodesPerStep = actualSteps == 0 ? ""
                    : String.format(Locale.ROOT, "%.3f", (double) totalBytecodes / actualSteps);
            return String.format(Locale.ROOT,
                    "%d,%d,%d,%d,%d,%d,%s,%d,%d,%d,%s,%s,%s,%s,%s,"
                            + "%d,%d,%s,%d,%s",
                    robotId, assignmentRound, start.x, start.y, target.x, target.y,
                    status, finishRound, actualRounds, actualSteps,
                    optimal, optimal, detour, overhead, efficiency,
                    observedTurns, totalBytecodes, meanBytecodes, maxBytecodesInTurn,
                    bytecodesPerStep);
        }

        int robotId() { return robotId; }
        int assignmentRound() { return assignmentRound; }
        MapLocation target() { return target; }
        int actualSteps() { return actualSteps; }
        int optimalSteps() { return optimalSteps; }
        boolean reached() { return reached; }
        int observedTurns() { return observedTurns; }
        long totalBytecodes() { return totalBytecodes; }
        int maxBytecodesInTurn() { return maxBytecodesInTurn; }
    }
}
