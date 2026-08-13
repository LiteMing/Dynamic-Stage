package vibe.liteming.dynamicstage.client.flight;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free playback of the deterministic subset imported from CMDCam. */
final class StageFlightPath {
    private final long durationMillis;
    private final int loops;
    private final String interpolation;
    private final boolean distanceBasedTiming;
    private final List<Point> points;
    private final Point reference;

    private StageFlightPath(long durationMillis, int loops, String interpolation,
                            boolean distanceBasedTiming, List<Point> points) {
        this.durationMillis = durationMillis;
        this.loops = loops;
        this.interpolation = interpolation;
        this.distanceBasedTiming = distanceBasedTiming;
        this.points = List.copyOf(points);
        this.reference = points.get(0);
    }

    static StageFlightPath parse(byte[] canonicalJson) {
        JsonObject scene = JsonParser.parseString(new String(canonicalJson, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray jsonPoints = scene.getAsJsonArray("points");
        List<Point> points = new ArrayList<>(jsonPoints.size());
        for (JsonElement element : jsonPoints) {
            JsonObject point = element.getAsJsonObject();
            points.add(new Point(
                    point.get("x").getAsDouble(),
                    point.get("y").getAsDouble(),
                    point.get("z").getAsDouble(),
                    point.get("rotationYaw").getAsDouble(),
                    point.get("rotationPitch").getAsDouble(),
                    point.get("roll").getAsDouble(),
                    point.get("zoom").getAsDouble()));
        }
        unwrapYaw(points, scene.has("pitch_mode") ? scene.get("pitch_mode").getAsInt() : 0);
        return new StageFlightPath(scene.get("duration").getAsLong(), scene.get("loop").getAsInt(),
                scene.get("inter").getAsString(), scene.has("d_timing")
                && scene.get("d_timing").getAsBoolean(), points);
    }

    @Nullable
    StageFlightPose sample(double elapsedMillis) {
        if (elapsedMillis < 0.0D) {
            return null;
        }

        List<Point> samplePoints = points;
        boolean closePath = loops != 0;
        long iteration = (long) Math.floor(elapsedMillis / durationMillis);
        double iterationMillis = elapsedMillis - iteration * (double) durationMillis;

        if (loops >= 0 && iteration > loops) {
            return null;
        }
        if (loops > 0 && iteration == loops) {
            closePath = false;
        }
        if (closePath) {
            samplePoints = new ArrayList<>(points.size() + 1);
            samplePoints.addAll(points);
            samplePoints.add(points.get(0));
        }

        double progress = Mth.clamp(iterationMillis / durationMillis, 0.0D, 1.0D);
        Point sampled = interpolate(samplePoints, progress, closePath, iteration > 0);
        return new StageFlightPose(
                new Vec3(sampled.x - reference.x, sampled.y - reference.y, sampled.z - reference.z),
                (float) (sampled.yaw - reference.yaw),
                (float) (sampled.pitch - reference.pitch),
                (float) (sampled.roll - reference.roll),
                (float) reference.fov,
                (float) sampled.fov);
    }

    private Point interpolate(List<Point> samplePoints, double progress, boolean closed, boolean repeated) {
        double[] times = createTimes(samplePoints, closed, repeated);
        int right = 1;
        while (right < times.length - 1 && times[right] < progress) {
            right++;
        }
        int left = right - 1;
        double span = times[right] - times[left];
        double local = span <= 0.0D ? 0.0D : (progress - times[left]) / span;
        Point before = boundary(samplePoints, left - 1, repeated, false);
        Point start = samplePoints.get(left);
        Point end = samplePoints.get(right);
        Point after = boundary(samplePoints, right + 1, closed, repeated && !closed);
        return Point.interpolate(interpolation, local, before, start, end, after);
    }

    private double[] createTimes(List<Point> samplePoints, boolean closed, boolean repeated) {
        int count = samplePoints.size();
        double[] times = new double[count];
        for (int i = 1; i < count; i++) {
            times[i] = i / (double) (count - 1);
        }
        if (!distanceBasedTiming || count <= 2) {
            return times;
        }

        double[] distances = new double[count - 1];
        double total = 0.0D;
        for (int segment = 0; segment < count - 1; segment++) {
            Point before = boundary(samplePoints, segment - 1, repeated, false);
            Point start = samplePoints.get(segment);
            Point end = samplePoints.get(segment + 1);
            Point after = boundary(samplePoints, segment + 2, closed, repeated && !closed);
            Point previous = start;
            double distance = 0.0D;
            for (int step = 1; step <= 4; step++) {
                Point current = Point.interpolate(interpolation, step * 0.25D,
                        before, start, end, after);
                distance += previous.distance(current);
                previous = current;
            }
            distances[segment] = distance;
            total += distance;
        }
        if (total <= 0.0D) {
            return times;
        }
        double accumulated = 0.0D;
        for (int i = 1; i < count - 1; i++) {
            accumulated += distances[i - 1] / total;
            times[i] = accumulated;
        }
        times[count - 1] = 1.0D;
        return times;
    }

    private Point boundary(List<Point> samplePoints, int index, boolean wrap, boolean clamp) {
        if (index >= 0 && index < samplePoints.size()) {
            return samplePoints.get(index);
        }
        if (wrap) {
            int wrapped = Math.floorMod(index, points.size());
            return points.get(wrapped);
        }
        Point edge = index < 0 ? samplePoints.get(0) : samplePoints.get(samplePoints.size() - 1);
        if (clamp) {
            return edge;
        }
        Point neighbor = index < 0 ? samplePoints.get(1)
                : samplePoints.get(samplePoints.size() - 2);
        return edge.extrapolateFrom(neighbor);
    }

    private static void unwrapYaw(List<Point> points, int pitchMode) {
        if (pitchMode == 0 || points.isEmpty()) {
            return;
        }
        double previousRaw = points.get(0).yaw;
        double adjusted = previousRaw;
        for (int i = 1; i < points.size(); i++) {
            Point point = points.get(i);
            double delta = point.yaw - previousRaw;
            delta = pitchMode == 1 ? Mth.wrapDegrees(delta) : delta % 360.0D;
            adjusted += delta;
            previousRaw = point.yaw;
            points.set(i, point.withYaw(adjusted));
        }
    }

    private record Point(double x, double y, double z, double yaw, double pitch, double roll, double fov) {
        private Point withYaw(double value) {
            return new Point(x, y, z, value, pitch, roll, fov);
        }

        private double distance(Point other) {
            double dx = other.x - x;
            double dy = other.y - y;
            double dz = other.z - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private Point extrapolateFrom(Point neighbor) {
            return new Point(
                    2.0D * x - neighbor.x,
                    2.0D * y - neighbor.y,
                    2.0D * z - neighbor.z,
                    2.0D * yaw - neighbor.yaw,
                    2.0D * pitch - neighbor.pitch,
                    2.0D * roll - neighbor.roll,
                    2.0D * fov - neighbor.fov);
        }

        private static Point interpolate(String type, double t, Point p0, Point p1, Point p2, Point p3) {
            if ("linear".equals(type)) {
                return map((a, b, c, d) -> a + (b - a) * t, p0, p1, p2, p3);
            }
            if ("cosine".equals(type)) {
                double amount = (1.0D - Math.cos(t * Math.PI)) * 0.5D;
                return map((a, b, c, d) -> a * (1.0D - amount) + b * amount, p0, p1, p2, p3);
            }
            if ("cubic".equals(type)) {
                return map((a, b, c, d) -> cubic(t, a, b, c, d), p0, p1, p2, p3);
            }
            return map((a, b, c, d) -> hermite(t, a, b, c, d), p0, p1, p2, p3);
        }

        private static Point map(ComponentInterpolator fn, Point p0, Point p1, Point p2, Point p3) {
            return new Point(
                    fn.value(p1.x, p2.x, p0.x, p3.x),
                    fn.value(p1.y, p2.y, p0.y, p3.y),
                    fn.value(p1.z, p2.z, p0.z, p3.z),
                    fn.value(p1.yaw, p2.yaw, p0.yaw, p3.yaw),
                    fn.value(p1.pitch, p2.pitch, p0.pitch, p3.pitch),
                    fn.value(p1.roll, p2.roll, p0.roll, p3.roll),
                    fn.value(p1.fov, p2.fov, p0.fov, p3.fov));
        }

        private static double cubic(double t, double start, double end, double before, double after) {
            double a = (after - end) - (before - start);
            double b = (before - start) - a;
            double c = end - before;
            return a * t * t * t + b * t * t + c * t + start;
        }

        private static double hermite(double t, double start, double end, double before, double after) {
            double tangentStart = (end - before) * 0.5D;
            double tangentEnd = (after - start) * 0.5D;
            double t2 = t * t;
            double t3 = t2 * t;
            return (2.0D * t3 - 3.0D * t2 + 1.0D) * start
                    + (t3 - 2.0D * t2 + t) * tangentStart
                    + (t3 - t2) * tangentEnd
                    + (-2.0D * t3 + 3.0D * t2) * end;
        }
    }

    @FunctionalInterface
    private interface ComponentInterpolator {
        double value(double start, double end, double before, double after);
    }
}
