package vibe.liteming.dynamicstage.client.backdrop;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Renders the Voxy backdrop DIRECTLY from raw section voxels — no bake step,
 * no .sdb intermediate, no column reconstruction. The mesh is built once into a
 * GPU-resident {@link VertexBuffer}; {@link #render} only binds and draws.
 * <p>
 * Surface culling: a face is only emitted when the neighbouring voxel is absent,
 * so underground blocks never produce geometry. Translucent (alpha &lt; 255)
 * voxels go into a second no-depth-write pass.
 */
public final class BackdropRenderer {

    @Nullable
    private static volatile VoxelMesh activeMesh;

    private BackdropRenderer() {
    }

    /**
     * Loads the backdrop for the given anchor from the given LOD data source:
     * voxel read on a background thread, mesh build on the client thread.
     *
     * @param dataFile Voxy storage directory or Distant Horizons sqlite file
     * @param source   {@link vibe.liteming.dynamicstage.stage.StageSession#SOURCE_VOXY}
     *                 or {@code SOURCE_DH}
     */
    public static void loadDirect(java.nio.file.Path dataFile, BlockPos anchor, String source) {
        CompletableFuture.runAsync(() -> {
            List<Voxel> voxels = StageSession.SOURCE_DH.equals(source)
                    ? DHFileReader.read(dataFile, anchor)
                    : VoxyDirectReader.read(dataFile, anchor);
            Minecraft.getInstance().execute(() -> {
                if (voxels.isEmpty()) {
                    clearBackdrop();
                } else {
                    setVoxels(voxels, anchor);
                }
            });
        });
    }

    /** Builds a new mesh from raw voxels (client thread; GPU upload). */
    public static void setVoxels(List<Voxel> voxels, BlockPos anchor) {
        VoxelMesh next = new VoxelMesh(voxels, anchor);
        VoxelMesh previous = activeMesh;
        activeMesh = next;
        if (previous != null) {
            previous.close();
        }
    }

    public static void clearBackdrop() {
        VoxelMesh previous = activeMesh;
        activeMesh = null;
        if (previous != null) {
            previous.close();
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        VoxelMesh mesh = activeMesh;
        Minecraft mc = Minecraft.getInstance();
        if (mesh == null || mc.player == null || !StageWorlds.isStageLevel(mc.level)) {
            return;
        }
        BlockPos anchor = mesh.anchor;
        Vec3 camera = event.getCamera().getPosition();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        // 6-DoF virtual camera: view = viewReal⁻¹ · viewVirtual.
        // While CMDCam plays, the virtual pose is the pose delta since playback
        // start (anchor-anchored): the LOD world translates AND rotates (XYZ)
        // with the camera. When no virtual camera is active the transform
        // degenerates to a plain anchor translation (absolute projection).
        CMDCamPoseBridge.Pose delta = CMDCamPoseBridge.poseDelta();
        if (delta != null) {
            applyVirtualCamera(poseStack, event.getCamera(), delta, anchor);
        } else {
            poseStack.translate(
                    -camera.x + anchor.getX(),
                    -camera.y + anchor.getY(),
                    -camera.z + anchor.getZ());
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        mesh.draw(poseStack.last().pose());

        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
    }

    /**
     * Applies M = viewReal⁻¹ · viewVirtual so the LOD world is viewed from the
     * virtual camera pose (anchor + delta): undo the real camera (translate +
     * yaw/pitch), then apply the virtual camera inverse (roll/pitch/yaw +
     * translate). Yaw/pitch/roll deltas are applied around the anchor centre,
     * giving full XYZ rotation of the backdrop.
     */
    private static void applyVirtualCamera(PoseStack poseStack, net.minecraft.client.Camera camera,
                                           CMDCamPoseBridge.Pose delta, BlockPos anchor) {
        Vec3 cam = camera.getPosition();
        // Undo real camera translation + rotation.
        poseStack.translate(cam.x, cam.y, cam.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

        // Apply virtual camera (anchored at `anchor`): inverse rotation then
        // inverse translation, with the anchor as the rotation centre.
        poseStack.mulPose(Axis.YP.rotationDegrees((float) -delta.yaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees((float) -delta.pitch()));
        poseStack.mulPose(Axis.ZP.rotationDegrees((float) -delta.roll()));
        poseStack.translate(
                -(anchor.getX() + delta.position().x),
                -(anchor.getY() + delta.position().y),
                -(anchor.getZ() + delta.position().z));
    }

    /** Immutable GPU-resident voxel mesh with surface culling. */
    static final class VoxelMesh {
        private final BlockPos anchor;
        private final VertexBuffer opaqueBuffer;
        private final int opaqueVertexCount;
        private final VertexBuffer translucentBuffer;
        private final int translucentVertexCount;
        private volatile boolean closed;

        VoxelMesh(List<Voxel> voxels, BlockPos anchor) {
            this.anchor = anchor;
            // Neighbour culling applies to fine (1×1×1) voxels only; coarse cells
            // are drawn as standalone boxes.
            Set<Long> present = new HashSet<>();
            for (Voxel v : voxels) {
                if (v.size() == 1 && v.height() == 1) {
                    present.add(pack(v.x(), v.y(), v.z()));
                }
            }

            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int opaqueQuads = 0;
            for (Voxel v : voxels) {
                if ((v.colorArgb() >>> 24) < 255) {
                    continue;
                }
                opaqueQuads += emitFaces(builder, v, present, rgba(v.colorArgb()));
            }
            this.opaqueVertexCount = opaqueQuads * 4;
            this.opaqueBuffer = upload(builder);

            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int translucentQuads = 0;
            for (Voxel v : voxels) {
                if ((v.colorArgb() >>> 24) >= 255) {
                    continue;
                }
                translucentQuads += emitFaces(builder, v, present, rgba(v.colorArgb()));
            }
            this.translucentVertexCount = translucentQuads * 4;
            this.translucentBuffer = upload(builder);
        }

        private static long pack(int x, int y, int z) {
            return ((long) x & 0xFFFFFFL) << 40 | ((long) y & 0xFFFL) << 28 | ((long) z & 0xFFFFFFL);
        }

        private static float[] rgba(int argb) {
            return new float[]{
                    ((argb >> 16) & 0xFF) / 255.0F,
                    ((argb >> 8) & 0xFF) / 255.0F,
                    (argb & 0xFF) / 255.0F,
                    ((argb >>> 24) & 0xFF) / 255.0F
            };
        }

        /** Emits faces for voxels whose neighbour is absent; returns quad count. */
        private static int emitFaces(BufferBuilder builder, Voxel v, Set<Long> present, float[] c) {
            int x = v.x();
            int y = v.y();
            int z = v.z();
            int sx = v.size();
            int sy = v.height();
            int xMax = x + sx;
            int yMax = y + sy;
            int zMax = z + sx;
            int quads = 0;
            if (!present.contains(pack(xMax, y, z))) {
                quad(builder, xMax, y, z, xMax, yMax, z, xMax, yMax, zMax, xMax, y, zMax, c);
                quads++;
            }
            if (!present.contains(pack(x - 1, y, z))) {
                quad(builder, x, y, z, x, y, zMax, x, yMax, zMax, x, yMax, z, c);
                quads++;
            }
            if (!present.contains(pack(x, yMax, z))) {
                quad(builder, x, yMax, z, x, yMax, zMax, xMax, yMax, zMax, xMax, yMax, z, c);
                quads++;
            }
            if (!present.contains(pack(x, y - 1, z))) {
                quad(builder, x, y, z, xMax, y, z, xMax, y, zMax, x, y, zMax, c);
                quads++;
            }
            if (!present.contains(pack(x, y, zMax))) {
                quad(builder, x, y, zMax, xMax, y, zMax, xMax, yMax, zMax, x, yMax, zMax, c);
                quads++;
            }
            if (!present.contains(pack(x, y, z - 1))) {
                quad(builder, x, y, z, x, yMax, z, xMax, yMax, z, xMax, y, z, c);
                quads++;
            }
            return quads;
        }

        private static void quad(BufferBuilder builder,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float[] c) {
            builder.vertex(x0, y0, z0).color(c[0], c[1], c[2], c[3]).endVertex();
            builder.vertex(x1, y1, z1).color(c[0], c[1], c[2], c[3]).endVertex();
            builder.vertex(x2, y2, z2).color(c[0], c[1], c[2], c[3]).endVertex();
            builder.vertex(x3, y3, z3).color(c[0], c[1], c[2], c[3]).endVertex();
        }

        private static VertexBuffer upload(BufferBuilder builder) {
            BufferBuilder.RenderedBuffer rendered = builder.end();
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(rendered);
            VertexBuffer.unbind();
            return buffer;
        }

        void draw(Matrix4f matrix) {
            if (opaqueVertexCount > 0) {
                RenderSystem.depthMask(true);
                opaqueBuffer.bind();
                opaqueBuffer.drawWithShader(matrix, RenderSystem.getProjectionMatrix(),
                        GameRenderer.getPositionColorShader());
                VertexBuffer.unbind();
            }
            if (translucentVertexCount > 0) {
                RenderSystem.depthMask(false);
                translucentBuffer.bind();
                translucentBuffer.drawWithShader(matrix, RenderSystem.getProjectionMatrix(),
                        GameRenderer.getPositionColorShader());
                VertexBuffer.unbind();
                RenderSystem.depthMask(true);
            }
        }

        void close() {
            if (!closed) {
                closed = true;
                opaqueBuffer.close();
                translucentBuffer.close();
            }
        }
    }
}
