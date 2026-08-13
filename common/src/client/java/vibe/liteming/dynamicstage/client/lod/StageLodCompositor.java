package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Filters only a native backend's LOD color texture before its original depth-aware composite. */
public final class StageLodCompositor {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageLodCompositor.class);
    private static final String VERTEX_SHADER = """
            #version 150 core
            out vec2 uv;
            void main() {
                vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = position;
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 150 core
            uniform sampler2D sourceTexture;
            uniform vec2 direction;
            uniform float radius;
            uniform float opacity;
            in vec2 uv;
            out vec4 color;
            void main() {
                ivec2 size = textureSize(sourceTexture, 0);
                ivec2 center = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
                float sigma = max(radius * 0.5, 0.5);
                vec4 sum = vec4(0.0);
                float total = 0.0;
                for (int i = -4; i <= 4; i++) {
                    float sampleOffset = float(i) * radius * 0.25;
                    float weight = exp(-0.5 * pow(sampleOffset / sigma, 2.0));
                    ivec2 offset = ivec2(round(direction * sampleOffset));
                    vec4 sampleColor = texelFetch(sourceTexture,
                            clamp(center + offset, ivec2(0), size - 1), 0);
                    sum.rgb += sampleColor.rgb * sampleColor.a * weight;
                    sum.a += sampleColor.a * weight;
                    total += weight;
                }
                color = sum / total;
                if (color.a > 0.0001) {
                    color.rgb /= color.a;
                }
                color.a *= opacity;
            }
            """;

    private static int program;
    private static int sourceUniform;
    private static int directionUniform;
    private static int radiusUniform;
    private static int opacityUniform;
    private static int framebuffer;
    private static int transparentTexture;
    private static int pingTexture;
    private static int outputTexture;
    private static int vertexArray;
    private static int width;
    private static int height;

    private StageLodCompositor() {
    }

    public static int filterColorTexture(int sourceTexture) {
        StageBackdropEffects.State effects = effects();
        if (effects == null || effects.passthrough()) {
            return sourceTexture;
        }
        GlState state = GlState.capture();
        try {
            if (effects.hidden()) {
                return transparentTexture();
            }
            ensureResources(sourceTexture);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDepthMask(false);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glViewport(0, 0, width, height);
            GL20C.glUseProgram(program);
            GL30C.glBindVertexArray(vertexArray);
            GL20C.glUniform1i(sourceUniform, 0);
            if (effects.blurRadius() <= 0.0001F) {
                drawPass(sourceTexture, outputTexture, 0.0F, 0.0F, 0.0F, effects.opacity());
                return outputTexture;
            }
            drawPass(sourceTexture, pingTexture, 1.0F, 0.0F, effects.blurRadius(), 1.0F);
            drawPass(pingTexture, outputTexture, 0.0F, 1.0F,
                    effects.blurRadius(), effects.opacity());
            return outputTexture;
        } finally {
            state.restore();
        }
    }

    private static int transparentTexture() {
        if (transparentTexture == 0) {
            transparentTexture = createTexture(1, 1);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 1, 1,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(4));
        }
        return transparentTexture;
    }

    public static boolean shouldSkipComposite() {
        StageBackdropEffects.State effects = effects();
        return effects != null && effects.hidden();
    }

    public static boolean needsDhBlending() {
        StageBackdropEffects.State effects = effects();
        return effects != null && effects.translucent();
    }

    @Nullable
    private static StageBackdropEffects.State effects() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (minecraft.level == null || snapshot == null || !StageWorlds.isStageLevel(minecraft.level)
                || !StageBackdropRuntime.isMounted(snapshot.instanceId())) {
            return null;
        }
        return StageBackdropEffects.sample(snapshot.clientScene(), minecraft.level.getGameTime(),
                minecraft.getFrameTime());
    }

    private static void drawPass(int source, int destination, float directionX, float directionY,
                                 float radius, float opacity) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, destination, 0);
        GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Stage LOD compositor framebuffer is incomplete: 0x"
                    + Integer.toHexString(status));
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, source);
        GL20C.glUniform2f(directionUniform, directionX, directionY);
        GL20C.glUniform1f(radiusUniform, radius);
        GL20C.glUniform1f(opacityUniform, opacity);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    }

    private static void ensureResources(int sourceTexture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sourceTexture);
        int sourceWidth = GL11C.glGetTexLevelParameteri(
                GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        int sourceHeight = GL11C.glGetTexLevelParameteri(
                GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalStateException("LOD backend supplied an invalid color texture");
        }
        if (program == 0) {
            program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            sourceUniform = GL20C.glGetUniformLocation(program, "sourceTexture");
            directionUniform = GL20C.glGetUniformLocation(program, "direction");
            radiusUniform = GL20C.glGetUniformLocation(program, "radius");
            opacityUniform = GL20C.glGetUniformLocation(program, "opacity");
            framebuffer = GL30C.glGenFramebuffers();
            vertexArray = GL30C.glGenVertexArrays();
            LOGGER.info("Initialized the stage LOD color compositor");
        }
        if (sourceWidth == width && sourceHeight == height) {
            return;
        }
        if (pingTexture != 0) {
            GL11C.glDeleteTextures(pingTexture);
            GL11C.glDeleteTextures(outputTexture);
        }
        width = sourceWidth;
        height = sourceHeight;
        pingTexture = createTexture(width, height);
        outputTexture = createTexture(width, height);
    }

    private static int createTexture(int textureWidth, int textureHeight) {
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, textureWidth, textureHeight,
                0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
        return texture;
    }

    private static int linkProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GL20C.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GL20C.GL_FRAGMENT_SHADER, fragmentSource);
        int linked = GL20C.glCreateProgram();
        GL20C.glAttachShader(linked, vertex);
        GL20C.glAttachShader(linked, fragment);
        GL20C.glLinkProgram(linked);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(linked);
            GL20C.glDeleteProgram(linked);
            throw new IllegalStateException("Could not link the stage LOD compositor: " + log);
        }
        return linked;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile the stage LOD compositor: " + log);
        }
        return shader;
    }

    private record GlState(int program, int drawFramebuffer, int readFramebuffer, int vertexArray,
                           int activeTexture, int texture, int[] viewport, boolean blend, boolean depth,
                           boolean scissor, boolean stencil, boolean cull, boolean depthMask,
                           boolean[] colorMask) {
        static GlState capture() {
            IntBuffer viewportBuffer = BufferUtils.createIntBuffer(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewportBuffer);
            int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            int texture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            ByteBuffer colorMaskBuffer = BufferUtils.createByteBuffer(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMaskBuffer);
            return new GlState(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                    GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), activeTexture, texture,
                    new int[]{viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3)},
                    GL11C.glIsEnabled(GL11C.GL_BLEND), GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST), GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
                    GL11C.glIsEnabled(GL11C.GL_CULL_FACE), GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    new boolean[]{colorMaskBuffer.get(0) != 0, colorMaskBuffer.get(1) != 0,
                            colorMaskBuffer.get(2) != 0, colorMaskBuffer.get(3) != 0});
        }

        void restore() {
            GL20C.glUseProgram(program);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30C.glBindVertexArray(vertexArray);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL13C.glActiveTexture(activeTexture);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            setEnabled(GL11C.GL_BLEND, blend);
            setEnabled(GL11C.GL_DEPTH_TEST, depth);
            setEnabled(GL11C.GL_SCISSOR_TEST, scissor);
            setEnabled(GL11C.GL_STENCIL_TEST, stencil);
            setEnabled(GL11C.GL_CULL_FACE, cull);
            GL11C.glDepthMask(depthMask);
            GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) {
                GL11C.glEnable(capability);
            } else {
                GL11C.glDisable(capability);
            }
        }
    }

}
