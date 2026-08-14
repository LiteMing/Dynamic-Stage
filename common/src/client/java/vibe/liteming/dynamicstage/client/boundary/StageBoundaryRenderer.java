package vibe.liteming.dynamicstage.client.boundary;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.client.config.StageClientConfig;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.world.StageWorlds;

/** Camera-relative translucent grid for the active instance's six virtual walls. */
public final class StageBoundaryRenderer {
    private static final double SEGMENT_RADIUS = 14.0D;
    private static final int GRID_STEP = 4;

    private StageBoundaryRenderer() {
    }

    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientStageSession.Snapshot session = ClientStageSession.active();
        if (player == null || minecraft.level == null || session == null
                || !StageWorlds.isStageLevel(minecraft.level)) {
            return;
        }

        StageBoundary boundary = session.boundary();
        StageClientConfig.BoundaryDisplay display = StageClientConfig.boundary();
        if (display.visibleDistance() <= 0.0D || display.opacity() <= 0.0F) {
            return;
        }
        AABB bounds = boundary.bounds(session.stageOrigin());
        Vec3 cameraPosition = camera.getPosition();
        int color = display.color(boundary.color());
        int red = color >> 16 & 255;
        int green = color >> 8 & 255;
        int blue = color & 255;

        poseStack.pushPose();
        poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        Matrix4f matrix = poseStack.last().pose();
        double x0 = clamp(player.getX() - SEGMENT_RADIUS, bounds.minX, bounds.maxX);
        double x1 = clamp(player.getX() + SEGMENT_RADIUS, bounds.minX, bounds.maxX);
        double y0 = clamp(player.getY() - SEGMENT_RADIUS, bounds.minY, bounds.maxY);
        double y1 = clamp(player.getY() + SEGMENT_RADIUS, bounds.minY, bounds.maxY);
        double z0 = clamp(player.getZ() - SEGMENT_RADIUS, bounds.minZ, bounds.maxZ);
        double z1 = clamp(player.getZ() + SEGMENT_RADIUS, bounds.minZ, bounds.maxZ);

        drawYZ(matrix, bounds.minX, y0, y1, z0, z1,
                fade(player.getX() - bounds.minX, display), red, green, blue, display.opacity());
        drawYZ(matrix, bounds.maxX, y0, y1, z0, z1,
                fade(bounds.maxX - player.getX(), display), red, green, blue, display.opacity());
        drawXY(matrix, bounds.minZ, x0, x1, y0, y1,
                fade(player.getZ() - bounds.minZ, display), red, green, blue, display.opacity());
        drawXY(matrix, bounds.maxZ, x0, x1, y0, y1,
                fade(bounds.maxZ - player.getZ(), display), red, green, blue, display.opacity());
        drawXZ(matrix, bounds.minY, x0, x1, z0, z1,
                fade(player.getY() - bounds.minY, display), red, green, blue, display.opacity());
        drawXZ(matrix, bounds.maxY, x0, x1, z0, z1,
                fade(bounds.maxY - player.getY(), display), red, green, blue, display.opacity());

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
    }

    private static void drawYZ(Matrix4f matrix, double x, double y0, double y1, double z0, double z1,
                               double fade, int red, int green, int blue, float opacity) {
        if (fade <= 0.0D) return;
        quad(matrix, fade, opacity, red, green, blue,
                x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1);
        GridWriter grid = beginGrid(fade, opacity);
        for (double y = gridStart(y0); y <= y1; y += GRID_STEP) grid.line(matrix, x, y, z0, x, y, z1, red, green, blue);
        for (double z = gridStart(z0); z <= z1; z += GRID_STEP) grid.line(matrix, x, y0, z, x, y1, z, red, green, blue);
        grid.draw();
    }

    private static void drawXY(Matrix4f matrix, double z, double x0, double x1, double y0, double y1,
                               double fade, int red, int green, int blue, float opacity) {
        if (fade <= 0.0D) return;
        quad(matrix, fade, opacity, red, green, blue,
                x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
        GridWriter grid = beginGrid(fade, opacity);
        for (double y = gridStart(y0); y <= y1; y += GRID_STEP) grid.line(matrix, x0, y, z, x1, y, z, red, green, blue);
        for (double x = gridStart(x0); x <= x1; x += GRID_STEP) grid.line(matrix, x, y0, z, x, y1, z, red, green, blue);
        grid.draw();
    }

    private static void drawXZ(Matrix4f matrix, double y, double x0, double x1, double z0, double z1,
                               double fade, int red, int green, int blue, float opacity) {
        if (fade <= 0.0D) return;
        quad(matrix, fade, opacity, red, green, blue,
                x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);
        GridWriter grid = beginGrid(fade, opacity);
        for (double x = gridStart(x0); x <= x1; x += GRID_STEP) grid.line(matrix, x, y, z0, x, y, z1, red, green, blue);
        for (double z = gridStart(z0); z <= z1; z += GRID_STEP) grid.line(matrix, x0, y, z, x1, y, z, red, green, blue);
        grid.draw();
    }

    private static void quad(Matrix4f matrix, double fade, float opacity,
                             int red, int green, int blue, double... coordinates) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < coordinates.length; i += 3) {
            vertex(builder, matrix, coordinates[i], coordinates[i + 1], coordinates[i + 2],
                    red, green, blue, (int) (36.0D * fade * opacity));
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private static GridWriter beginGrid(double fade, float opacity) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth((float) Mth.clamp(1.0D + fade * 2.0D, 1.0D, 3.0D));
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        return new GridWriter(builder, (int) (155.0D * fade * opacity));
    }

    private static void vertex(BufferBuilder builder, Matrix4f matrix, double x, double y, double z,
                               int red, int green, int blue, int alpha) {
        builder.vertex(matrix, (float) x, (float) y, (float) z).color(red, green, blue, alpha).endVertex();
    }

    private static double fade(double distance, StageClientConfig.BoundaryDisplay display) {
        double normalized = 1.0D - Mth.clamp(
                Math.abs(distance) / display.visibleDistance(), 0.0D, 1.0D);
        return normalized * normalized;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double gridStart(double value) {
        return Math.ceil(value / GRID_STEP) * GRID_STEP;
    }

    private record GridWriter(BufferBuilder builder, int alpha) {
        private void line(Matrix4f matrix, double x0, double y0, double z0, double x1, double y1, double z1,
                          int red, int green, int blue) {
            vertex(builder, matrix, x0, y0, z0, red, green, blue, alpha);
            vertex(builder, matrix, x1, y1, z1, red, green, blue, alpha);
        }

        private void draw() {
            BufferUploader.drawWithShader(builder.end());
        }
    }
}
