package vibe.liteming.dynamicstage.flight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
