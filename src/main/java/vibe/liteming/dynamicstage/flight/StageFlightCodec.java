package vibe.liteming.dynamicstage.flight;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Validates and selects deterministic client-only scenes from CMDCam exports. */
public final class StageFlightCodec {

    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SCENES = 10;
    public static final int MAX_POINTS = 4096;
    public static final long MIN_DURATION_MILLIS = 100L;
    public static final long MAX_DURATION_MILLIS = 60L * 60L * 1000L;

    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_NODES = 100_000;
    private static final int MAX_STRING_LENGTH = 1024;
    private static final double MAX_COORDINATE = 30_000_000.0D;
    private static final double MAX_ANGLE = 1_000_000.0D;
    private static final Set<String> INTERPOLATIONS = Set.of(
            "linear", "cubic", "hermite", "cosine", "circular", "invcircular");
    private static final Gson GSON = new Gson();

    private StageFlightCodec() {
    }

    /** Selects a one-based CMDCam scene slot and returns a canonical single-scene object. */
    public static Scene select(byte[] exportBytes, int sceneSlot) throws IOException {
        String json = decodeAndScan(exportBytes);
        JsonElement root = parse(json);
        if (!root.isJsonArray()) {
            throw new IOException("CMDCam export root must be a scene array");
        }
        JsonArray scenes = root.getAsJsonArray();
        if (scenes.isEmpty() || scenes.size() > MAX_SCENES) {
            throw new IOException("CMDCam export must contain 1-" + MAX_SCENES + " scenes");
        }
        if (sceneSlot < 1 || sceneSlot > scenes.size()) {
            throw new IOException("CMDCam scene slot " + sceneSlot + " is not present in this export");
        }
        return validateAndCanonicalize(scenes.get(sceneSlot - 1));
    }

    /** Validates the canonical single-scene representation used on disk and over the network. */
    public static Scene readSingle(byte[] sceneBytes) throws IOException {
        String json = decodeAndScan(sceneBytes);
        return validateAndCanonicalize(parse(json));
    }

    private static Scene validateAndCanonicalize(JsonElement element) throws IOException {
        if (!element.isJsonObject()) {
            throw new IOException("Selected CMDCam scene must be a JSON object");
        }
        JsonObject scene = element.getAsJsonObject();
        long duration = requiredLong(scene, "duration");
        if (duration < MIN_DURATION_MILLIS || duration > MAX_DURATION_MILLIS) {
            throw new IOException("CMDCam duration must be between " + MIN_DURATION_MILLIS
                    + " and " + MAX_DURATION_MILLIS + " milliseconds");
        }
        if (requiredInt(scene, "loop") != 0) {
            throw new IOException("Stage flights must not loop");
        }
        if (!"outside".equals(requiredString(scene, "mode"))) {
            throw new IOException("Stage flights require CMDCam outside mode");
        }
        String interpolation = requiredString(scene, "inter");
        if (!INTERPOLATIONS.contains(interpolation)) {
            throw new IOException("Unsupported CMDCam interpolation: " + interpolation);
        }
        if (scene.has("look_target") || scene.has("pos_target")) {
            throw new IOException("Stage flights cannot use entity or position follow targets");
        }
        if (optionalBoolean(scene, "smooth_start", false)) {
            throw new IOException("Stage flights cannot use smooth start");
        }
        int pitchMode = optionalInt(scene, "pitch_mode", 0);
        if (pitchMode < 0 || pitchMode > 2) {
            throw new IOException("Invalid CMDCam pitch mode");
        }
        boolean distanceBasedTiming = optionalBoolean(scene, "d_timing", false);
        optionalObject(scene, "pitch");
        optionalObject(scene, "yaw");
        optionalObject(scene, "pos");

        JsonElement pointsElement = scene.get("points");
        if (pointsElement == null || !pointsElement.isJsonArray()) {
            throw new IOException("CMDCam scene is missing its points array");
        }
        JsonArray points = pointsElement.getAsJsonArray();
        if (points.size() < 2 || points.size() > MAX_POINTS) {
            throw new IOException("CMDCam scene must contain 2-" + MAX_POINTS + " points");
        }
        double pathLength = 0.0D;
        Point previous = null;
        for (int i = 0; i < points.size(); i++) {
            Point point = validatePoint(points.get(i), i);
            if (previous != null) {
                pathLength += Math.sqrt(Math.pow(point.x - previous.x, 2.0D)
                        + Math.pow(point.y - previous.y, 2.0D)
                        + Math.pow(point.z - previous.z, 2.0D));
            }
            previous = point;
        }
        if (distanceBasedTiming && pathLength <= 0.0D) {
            throw new IOException("Distance-timed CMDCam scenes require a non-zero path length");
        }

        byte[] canonical = GSON.toJson(scene).getBytes(StandardCharsets.UTF_8);
        if (canonical.length > MAX_BYTES) {
            throw new IOException("Selected CMDCam scene exceeds " + MAX_BYTES + " bytes");
        }
        return new Scene(canonical, duration, points.size());
    }

    private static Point validatePoint(JsonElement element, int index) throws IOException {
        if (!element.isJsonObject()) {
            throw new IOException("CMDCam point " + index + " must be an object");
        }
        JsonObject point = element.getAsJsonObject();
        double x = boundedDouble(point, "x", MAX_COORDINATE);
        double y = boundedDouble(point, "y", MAX_COORDINATE);
        double z = boundedDouble(point, "z", MAX_COORDINATE);
        boundedDouble(point, "rotationYaw", MAX_ANGLE);
        boundedDouble(point, "rotationPitch", MAX_ANGLE);
        boundedDouble(point, "roll", MAX_ANGLE);
        double zoom = requiredDouble(point, "zoom");
        if (zoom <= 0.0D || zoom >= 180.0D) {
            throw new IOException("CMDCam point " + index + " has an invalid zoom");
        }
        return new Point(x, y, z);
    }

    private static double boundedDouble(JsonObject object, String name, double limit) throws IOException {
        double value = requiredDouble(object, name);
        if (Math.abs(value) > limit) {
            throw new IOException("CMDCam value '" + name + "' exceeds the supported range");
        }
        return value;
    }

    private static double requiredDouble(JsonObject object, String name) throws IOException {
        JsonPrimitive primitive = requiredNumber(object, name);
        double value;
        try {
            value = Double.parseDouble(primitive.getAsString());
        } catch (NumberFormatException e) {
            throw new IOException("CMDCam value '" + name + "' is not numeric", e);
        }
        if (!Double.isFinite(value)) {
            throw new IOException("CMDCam value '" + name + "' must be finite");
        }
        return value;
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        try {
            return new BigDecimal(requiredNumber(object, name).getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("CMDCam value '" + name + "' must be an integer", e);
        }
    }

    private static int requiredInt(JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("CMDCam value '" + name + "' is outside the integer range");
        }
        return (int) value;
    }

    private static int optionalInt(JsonObject object, String name, int fallback) throws IOException {
        return object.has(name) ? requiredInt(object, name) : fallback;
    }

    private static JsonPrimitive requiredNumber(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("CMDCam scene is missing numeric '" + name + "'");
        }
        return element.getAsJsonPrimitive();
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("CMDCam scene is missing string '" + name + "'");
        }
        return element.getAsString();
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean fallback) throws IOException {
        if (!object.has(name)) {
            return fallback;
        }
        JsonElement element = object.get(name);
        if (!element.isJsonPrimitive()) {
            throw new IOException("CMDCam value '" + name + "' must be boolean or 0/1");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            String value = primitive.getAsString();
            if ("0".equals(value)) {
                return false;
            }
            if ("1".equals(value)) {
                return true;
            }
        }
        throw new IOException("CMDCam value '" + name + "' must be boolean or 0/1");
    }

    private static void optionalObject(JsonObject object, String name) throws IOException {
        if (object.has(name) && !object.get(name).isJsonObject()) {
            throw new IOException("CMDCam value '" + name + "' must be an object");
        }
    }

    private static JsonElement parse(String json) throws IOException {
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException("Invalid CMDCam JSON: " + e.getMessage(), e);
        }
    }

    private static String decodeAndScan(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("CMDCam JSON size must be between 1 and " + MAX_BYTES + " bytes");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("CMDCam JSON is not valid UTF-8", e);
        }
        scanStructure(json);
        return json;
    }

    private static void scanStructure(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            int depth = 0;
            int nodes = 0;
            while (true) {
                JsonToken token = reader.peek();
                if (++nodes > MAX_JSON_NODES) {
                    throw new IOException("CMDCam JSON contains too many values");
                }
                switch (token) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        if (++depth > MAX_JSON_DEPTH) {
                            throw new IOException("CMDCam JSON nesting is too deep");
                        }
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        if (++depth > MAX_JSON_DEPTH) {
                            throw new IOException("CMDCam JSON nesting is too deep");
                        }
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> checkString(reader.nextName());
                    case STRING, NUMBER -> checkString(reader.nextString());
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    case END_DOCUMENT -> {
                        if (depth != 0) {
                            throw new IOException("CMDCam JSON ended inside a container");
                        }
                        return;
                    }
                }
            }
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid CMDCam JSON: " + e.getMessage(), e);
        }
    }

    private static void checkString(String value) throws IOException {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IOException("CMDCam JSON string exceeds " + MAX_STRING_LENGTH + " characters");
        }
    }

    public record Scene(byte[] json, long durationMillis, int pointCount) {
        public Scene {
            json = json.clone();
        }

        @Override
        public byte[] json() {
            return json.clone();
        }
    }

    private record Point(double x, double y, double z) {
    }
}
