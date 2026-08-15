package vibe.liteming.dynamicstage.client.boundary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageBoundaryRendererTest {

    @Test
    void crossedFaceRemainsFullyVisible() {
        assertEquals(1.0D, StageBoundaryRenderer.faceVisibility(-0.01D, 16.0D));
        assertEquals(1.0D, StageBoundaryRenderer.faceVisibility(-1024.0D, 16.0D));
        assertEquals(1.0D, StageBoundaryRenderer.faceVisibility(-1.0D, 0.0D));
    }

    @Test
    void insideFaceFadesWithinConfiguredDistance() {
        assertEquals(1.0D, StageBoundaryRenderer.faceVisibility(0.0D, 16.0D));
        assertEquals(0.25D, StageBoundaryRenderer.faceVisibility(8.0D, 16.0D));
        assertEquals(0.0D, StageBoundaryRenderer.faceVisibility(16.0D, 16.0D));
        assertEquals(0.0D, StageBoundaryRenderer.faceVisibility(32.0D, 16.0D));
        assertEquals(0.0D, StageBoundaryRenderer.faceVisibility(0.0D, 0.0D));
    }
}
