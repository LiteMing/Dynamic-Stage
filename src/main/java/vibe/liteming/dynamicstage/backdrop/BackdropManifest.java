package vibe.liteming.dynamicstage.backdrop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Manifest of a baked backdrop blob.
 * <p>
 * Pure-Java data holder (no Minecraft classes) so the format can be read/written
 * by offline tools and unit tests without a MC runtime. Serialised as JSON via
 * {@link #toJson()} / {@link #fromJson(String)}.
 * <p>
 * Range is either a circular region ({@code radius}) or a spline corridor
 * ({@code corridorPoints} + {@code corridorRadius}); exactly one is set.
 */
public final class BackdropManifest {

    public static final int FORMAT_VERSION = 1;

    private final int formatVersion;
    private final String stageId;
    private final String sourceDim;          // ResourceLocation string, e.g. "minecraft:overworld"
    private final int anchorX, anchorY, anchorZ;
    private final String provider;           // "voxy_file" | "anvil" ...
    private final int providerFormatVersion;
    private final String worldHash;
    private final long bakeTime;
    // Region vs corridor (mutually exclusive)
    private final int radius;
    private final String corridorType;
    private final List<int[]> corridorPoints;
    private final int corridorRadius;
    // Visual data
    private final int[] palette;             // ARGB ints, indexed by payload columns
    private final int horizonColor;          // RGB int, used for fogMatch
    private final int[] lodLevels;
    private final boolean dirty;
    private final double blurSigma;
    private final double saturation;
    private final double brightness;
    private final boolean fogMatch;
    private final boolean parallaxLock;
    private final boolean motionFreeze;

    public BackdropManifest(int formatVersion, String stageId, String sourceDim,
                            int anchorX, int anchorY, int anchorZ,
                            String provider, int providerFormatVersion, String worldHash,
                            long bakeTime, int radius, String corridorType,
                            List<int[]> corridorPoints, int corridorRadius,
                            int[] palette, int horizonColor, int[] lodLevels, boolean dirty,
                            double blurSigma, double saturation, double brightness,
                            boolean fogMatch, boolean parallaxLock, boolean motionFreeze) {
        this.formatVersion = formatVersion;
        this.stageId = stageId;
        this.sourceDim = sourceDim;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.provider = provider;
        this.providerFormatVersion = providerFormatVersion;
        this.worldHash = worldHash;
        this.bakeTime = bakeTime;
        this.radius = radius;
        this.corridorType = corridorType;
        this.corridorPoints = corridorPoints == null ? List.of() : List.copyOf(corridorPoints);
        this.corridorRadius = corridorRadius;
        this.palette = palette == null ? new int[0] : palette.clone();
        this.horizonColor = horizonColor;
        this.lodLevels = lodLevels == null ? new int[]{0} : lodLevels.clone();
        this.dirty = dirty;
        this.blurSigma = blurSigma;
        this.saturation = saturation;
        this.brightness = brightness;
        this.fogMatch = fogMatch;
        this.parallaxLock = parallaxLock;
        this.motionFreeze = motionFreeze;
    }

    public int getFormatVersion() { return formatVersion; }
    public String getStageId() { return stageId; }
    public String getSourceDim() { return sourceDim; }
    public int getAnchorX() { return anchorX; }
    public int getAnchorY() { return anchorY; }
    public int getAnchorZ() { return anchorZ; }
    public String getProvider() { return provider; }
    public int getProviderFormatVersion() { return providerFormatVersion; }
    public String getWorldHash() { return worldHash; }
    public long getBakeTime() { return bakeTime; }
    public boolean isCorridor() { return radius <= 0; }
    public int getRadius() { return radius; }
    public String getCorridorType() { return corridorType; }
    public List<int[]> getCorridorPoints() { return corridorPoints; }
    public int getCorridorRadius() { return corridorRadius; }
    public int[] getPalette() { return palette.clone(); }
    public int getHorizonColor() { return horizonColor; }
    public int[] getLodLevels() { return lodLevels.clone(); }
    public boolean isDirty() { return dirty; }
    public double getBlurSigma() { return blurSigma; }
    public double getSaturation() { return saturation; }
    public double getBrightness() { return brightness; }
    public boolean isFogMatch() { return fogMatch; }
    public boolean isParallaxLock() { return parallaxLock; }
    public boolean isMotionFreeze() { return motionFreeze; }

    // ──────────────────────── JSON ────────────────────────

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", formatVersion);
        root.addProperty("stageId", stageId);
        JsonObject sw = new JsonObject();
        sw.addProperty("dim", sourceDim);
        JsonArray anchor = new JsonArray();
        anchor.add(anchorX);
        anchor.add(anchorY);
        anchor.add(anchorZ);
        sw.add("anchor", anchor);
        root.add("sourceWorld", sw);
        root.addProperty("provider", provider);
        root.addProperty("providerFormatVersion", providerFormatVersion);
        root.addProperty("worldHash", worldHash);
        root.addProperty("bakeTime", bakeTime);
        if (radius > 0) {
            JsonObject region = new JsonObject();
            region.addProperty("radius", radius);
            root.add("region", region);
        } else {
            JsonObject corridor = new JsonObject();
            corridor.addProperty("type", corridorType);
            corridor.addProperty("corridorRadius", corridorRadius);
            JsonArray pts = new JsonArray();
            for (int[] p : corridorPoints) {
                JsonArray pt = new JsonArray();
                pt.add(p[0]);
                pt.add(p[1]);
                pt.add(p[2]);
                pts.add(pt);
            }
            corridor.add("points", pts);
            root.add("pathCorridor", corridor);
        }
        JsonArray paletteArr = new JsonArray();
        for (int color : palette) {
            paletteArr.add(color);
        }
        root.add("palette", paletteArr);
        root.addProperty("horizonColor", horizonColor);
        JsonArray lodArr = new JsonArray();
        for (int lod : lodLevels) {
            lodArr.add(lod);
        }
        root.add("lodLevels", lodArr);
        root.addProperty("dirty", dirty);
        JsonObject visual = new JsonObject();
        visual.addProperty("blurSigma", blurSigma);
        visual.addProperty("saturation", saturation);
        visual.addProperty("brightness", brightness);
        visual.addProperty("fogMatch", fogMatch);
        visual.addProperty("parallaxLock", parallaxLock);
        visual.addProperty("motionFreeze", motionFreeze);
        root.add("visualDefaults", visual);
        return root.toString();
    }

    public static BackdropManifest fromJson(String json) {
        try {
            return parseJson(json);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Backdrop manifest JSON: " + e.getMessage(), e);
        }
    }

    private static BackdropManifest parseJson(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Backdrop manifest JSON: " + e.getMessage(), e);
        }
        int version = reqInt(root, "formatVersion");
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Backdrop format version " + version
                    + " (expected " + FORMAT_VERSION + ")");
        }
        String stageId = reqString(root, "stageId");
        String provider = reqString(root, "provider");
        String worldHash = reqString(root, "worldHash");
        long bakeTime = reqLong(root, "bakeTime");
        int providerFormatVersion = optInt(root, "providerFormatVersion", 0);
        int horizonColor = optInt(root, "horizonColor", 0xFF000000);
        boolean dirty = optBool(root, "dirty", false);

        JsonObject sw = reqObject(root, "sourceWorld");
        String sourceDim = reqString(sw, "dim");
        JsonArray anchor = reqArray(sw, "anchor");
        int anchorX = anchor.get(0).getAsInt();
        int anchorY = anchor.get(1).getAsInt();
        int anchorZ = anchor.get(2).getAsInt();

        int radius = 0;
        String corridorType = null;
        List<int[]> corridorPoints = List.of();
        int corridorRadius = 0;
        if (root.has("region")) {
            radius = reqInt(reqObject(root, "region"), "radius");
        } else if (root.has("pathCorridor")) {
            JsonObject corridor = reqObject(root, "pathCorridor");
            corridorType = reqString(corridor, "type");
            corridorRadius = reqInt(corridor, "corridorRadius");
            JsonArray pts = reqArray(corridor, "points");
            corridorPoints = new ArrayList<>(pts.size());
            for (JsonElement e : pts) {
                JsonArray pt = e.getAsJsonArray();
                corridorPoints.add(new int[]{pt.get(0).getAsInt(), pt.get(1).getAsInt(), pt.get(2).getAsInt()});
            }
        }

        int[] palette = new int[0];
        if (root.has("palette")) {
            JsonArray paletteArr = root.getAsJsonArray("palette");
            palette = new int[paletteArr.size()];
            for (int i = 0; i < paletteArr.size(); i++) {
                palette[i] = paletteArr.get(i).getAsInt();
            }
        }

        int[] lodLevels = new int[]{0};
        if (root.has("lodLevels")) {
            JsonArray lodArr = root.getAsJsonArray("lodLevels");
            lodLevels = new int[lodArr.size()];
            for (int i = 0; i < lodArr.size(); i++) {
                lodLevels[i] = lodArr.get(i).getAsInt();
            }
        }

        double blurSigma = 2.0D;
        double saturation = 0.8D;
        double brightness = 0.9D;
        boolean fogMatch = true;
        boolean parallaxLock = false;
        boolean motionFreeze = true;
        if (root.has("visualDefaults")) {
            JsonObject visual = reqObject(root, "visualDefaults");
            blurSigma = optDouble(visual, "blurSigma", blurSigma);
            saturation = optDouble(visual, "saturation", saturation);
            brightness = optDouble(visual, "brightness", brightness);
            fogMatch = optBool(visual, "fogMatch", fogMatch);
            parallaxLock = optBool(visual, "parallaxLock", parallaxLock);
            motionFreeze = optBool(visual, "motionFreeze", motionFreeze);
        }

        return new BackdropManifest(version, stageId, sourceDim,
                anchorX, anchorY, anchorZ, provider, providerFormatVersion, worldHash,
                bakeTime, radius, corridorType, corridorPoints, corridorRadius,
                palette, horizonColor, lodLevels, dirty,
                blurSigma, saturation, brightness, fogMatch, parallaxLock, motionFreeze);
    }

    private static int reqInt(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' in manifest");
        }
        return e.getAsInt();
    }

    private static long reqLong(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' in manifest");
        }
        return e.getAsLong();
    }

    private static String reqString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' in manifest");
        }
        return e.getAsString();
    }

    private static boolean optBool(JsonObject obj, String key, boolean def) {
        JsonElement e = obj.get(key);
        return e == null ? def : e.getAsBoolean();
    }

    private static int optInt(JsonObject obj, String key, int def) {
        JsonElement e = obj.get(key);
        return e == null ? def : e.getAsInt();
    }

    private static double optDouble(JsonObject obj, String key, double def) {
        JsonElement e = obj.get(key);
        return e == null ? def : e.getAsDouble();
    }

    private static JsonObject reqObject(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' in manifest");
        }
        return e.getAsJsonObject();
    }

    private static JsonArray reqArray(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonArray()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' in manifest");
        }
        return e.getAsJsonArray();
    }

    @Override
    public String toString() {
        return "BackdropManifest{stageId=" + stageId + ", provider=" + provider + ", version=" + formatVersion + "}";
    }
}
