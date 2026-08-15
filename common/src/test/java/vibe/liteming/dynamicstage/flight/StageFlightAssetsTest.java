package vibe.liteming.dynamicstage.flight;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageFlightAssetsTest {

    @TempDir
    Path worldRoot;

    @Test
    void importsOnlyFromInboxAndStoresStageIdAsHash() throws Exception {
        Path inbox = StageFlightAssets.inboxDirectory(worldRoot);
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("boss_intro.json"),
                "[" + StageFlightCodecTest.scene(3000, "outside", 0, 2) + "]", StandardCharsets.UTF_8);

        StageFlightAssets.Asset asset = StageFlightAssets.importFromInbox(
                worldRoot, "../../unsafe:stage", "boss_intro", 1);

        assertTrue(asset.path().startsWith(worldRoot.toAbsolutePath().normalize()));
        assertFalse(asset.path().toString().contains("unsafe"));
        assertTrue(StageFlightAssets.findConfigured(worldRoot, "../../unsafe:stage") != null);
    }

    @Test
    void rejectsCorruptedConfiguredAssetAndUnsafeImportName() throws Exception {
        Path inbox = StageFlightAssets.inboxDirectory(worldRoot);
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("flight.json"),
                "[" + StageFlightCodecTest.scene(3000, "outside", 0, 2) + "]", StandardCharsets.UTF_8);
        StageFlightAssets.Asset asset = StageFlightAssets.importFromInbox(worldRoot, "stage", "flight", 1);
        Files.writeString(asset.path(), "{}", StandardCharsets.UTF_8);

        assertNull(StageFlightAssets.findConfigured(worldRoot, "stage"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> StageFlightAssets.importFromInbox(worldRoot, "stage", "../flight", 1));
    }

    @Test
    void importsAndListsCMDCamSavedDataScenes() throws Exception {
        CompoundTag scenes = new CompoundTag();
        scenes.put("boss intro", cmdcamScene());
        CompoundTag root = new CompoundTag();
        root.put("data", scenes);
        root.putInt("DataVersion", 3465);
        Files.createDirectories(worldRoot.resolve("data"));
        try (var output = Files.newOutputStream(StageFlightAssets.cmdcamFile(worldRoot))) {
            NbtIo.writeCompressed(root, output);
        }

        assertEquals(java.util.List.of("boss intro"), StageFlightAssets.listCMDCamScenes(worldRoot));
        StageFlightAssets.Asset asset = StageFlightAssets.importFromCMDCam(worldRoot, "boss", "boss intro");
        assertEquals(2, asset.pointCount());
        assertEquals(3000L, asset.durationMillis());
        assertTrue(StageFlightAssets.findConfigured(worldRoot, "boss") != null);
    }

    @Test
    void readsModernDimensionScopedCMDCamDataAndExternalFiles() throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag scenes = new CompoundTag();
        scenes.put("scarlet", cmdcamScene());
        root.put("data", scenes);
        Path modern = worldRoot.resolve("dimensions/minecraft/overworld/data/cmdcam/scenes.dat");
        Files.createDirectories(modern.getParent());
        try (var output = Files.newOutputStream(modern)) {
            NbtIo.writeCompressed(root, output);
        }

        assertEquals(java.util.List.of("scarlet"), StageFlightAssets.listCMDCamScenes(worldRoot));
        StageFlightAssets.Asset asset = StageFlightAssets.importFromCMDCam(worldRoot, "modern", "scarlet");
        assertEquals(2, asset.pointCount());

        Path external = worldRoot.resolve("external-scenes.dat");
        Files.copy(modern, external);
        StageFlightAssets.Asset externalAsset = StageFlightAssets.importFromCMDCamFile(
                external, worldRoot, "external", null);
        assertEquals(asset.sceneJson().length, externalAsset.sceneJson().length);
    }

    @Test
    void storesGlobalFlightsAsDirectlyEditableJson() throws Exception {
        Path library = worldRoot.resolve("dynamicstage-library");
        StageFlightCodec.Scene initial = StageFlightCodec.readSingle(
                StageFlightCodecTest.scene(3000, "default", -1, 2).getBytes(StandardCharsets.UTF_8));

        StageFlightAssets.writeLibrary(library, "boss_intro", initial);

        Path json = library.resolve("boss_intro.json");
        assertTrue(Files.isRegularFile(json));
        assertTrue(Files.readString(json).startsWith("{"));
        assertTrue(Files.readAllLines(json).stream().anyMatch(line -> line.startsWith("  \"duration\"")));
        assertFalse(Files.exists(library.resolve("boss_intro.dat")));
        assertEquals(java.util.List.of("boss_intro"), StageFlightAssets.listLibraryFlights(library));
        assertEquals(3000L, StageFlightAssets.readLibrary(library, "boss_intro").durationMillis());

        Files.writeString(json, StageFlightCodecTest.scene(9000, "outside", 0, 3),
                StandardCharsets.UTF_8);
        assertEquals(9000L, StageFlightAssets.readLibrary(library, "boss_intro").durationMillis());
        assertEquals(3, StageFlightAssets.readLibrary(library, "boss_intro").pointCount());
    }

    @Test
    void ignoresRetiredDatFilesAndRejectsInvalidJson() throws Exception {
        Path library = worldRoot.resolve("dynamicstage-library");
        Files.createDirectories(library);
        Files.writeString(library.resolve("legacy.dat"), "retired", StandardCharsets.UTF_8);
        Files.writeString(library.resolve("broken.json"), "{}", StandardCharsets.UTF_8);

        assertTrue(StageFlightAssets.listLibraryFlights(library).contains("broken"));
        assertFalse(StageFlightAssets.listLibraryFlights(library).contains("legacy"));
        assertThrows(java.io.IOException.class, () -> StageFlightAssets.readLibrary(library, "broken"));
        assertThrows(java.io.IOException.class, () -> StageFlightAssets.readLibrary(library, "legacy"));
    }

    private static CompoundTag cmdcamScene() {
        CompoundTag scene = new CompoundTag();
        scene.putLong("duration", 3000L);
        scene.putInt("loop", 0);
        scene.putString("mode", "outside");
        scene.putString("inter", "linear");
        scene.putBoolean("smooth_start", false);
        scene.putInt("pitch_mode", 0);
        scene.putBoolean("d_timing", false);
        ListTag points = new ListTag();
        points.add(cmdcamPoint(8.5D, 66.0D, -96.5D, 0.0D));
        points.add(cmdcamPoint(16.5D, 70.0D, -88.5D, 90.0D));
        scene.put("points", points);
        return scene;
    }

    private static CompoundTag cmdcamPoint(double x, double y, double z, double yaw) {
        CompoundTag point = new CompoundTag();
        point.putDouble("x", x);
        point.putDouble("y", y);
        point.putDouble("z", z);
        point.putDouble("rotationYaw", yaw);
        point.putDouble("rotationPitch", 0.0D);
        point.putDouble("roll", 0.0D);
        point.putDouble("zoom", 70.0D);
        return point;
    }
}
