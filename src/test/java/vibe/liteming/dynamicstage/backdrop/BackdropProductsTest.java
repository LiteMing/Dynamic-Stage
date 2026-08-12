package vibe.liteming.dynamicstage.backdrop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackdropProductsTest {

    @TempDir
    Path worldRoot;

    @Test
    void storesAndResolvesByContentHashWithoutUsingStageIdAsPath() throws Exception {
        byte[] bytes = "portable backdrop".getBytes(StandardCharsets.UTF_8);
        String requestKey = "0123456789abcdef01234567";

        BackdropProducts.Product product = BackdropProducts.store(
                worldRoot, "../../unsafe:stage", bytes, requestKey);

        assertTrue(product.path().startsWith(worldRoot.toAbsolutePath().normalize()));
        assertFalse(product.path().toString().contains("unsafe"));
        assertEquals(64, product.hash().length());
        assertArrayEquals(bytes, Files.readAllBytes(product.path()));
        assertEquals(product, BackdropProducts.findByRequest(worldRoot, "../../unsafe:stage", requestKey));
    }

    @Test
    void rejectsCorruptedReferencedProduct() throws Exception {
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        String requestKey = "fedcba9876543210fedcba98";
        BackdropProducts.Product product = BackdropProducts.store(worldRoot, "stage", bytes, requestKey);
        Files.writeString(product.path(), "corrupt", StandardCharsets.UTF_8);

        assertNull(BackdropProducts.findByRequest(worldRoot, "stage", requestKey));
        assertNull(BackdropProducts.resolve(worldRoot, "stage", "../not-a-hash"));
    }
}
