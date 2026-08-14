package vibe.liteming.dynamicstage.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.client.command.StageLodClientCommands;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageTemplatePackets;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Vanilla, dependency-free editor for portable stage template settings. */
public final class StageTemplateEditorScreen extends Screen {
    private static final int FIELD_HEIGHT = 18;
    private static final int ROW_HEIGHT = 23;

    private final List<Label> labels = new ArrayList<>();
    private final List<EditBox> editBoxes = new ArrayList<>();
    private Draft draft;
    private Tab tab = Tab.STAGE;
    private String status = "";
    private long observedRevision = -1L;
    private int selectedTemplate = -1;

    private EditBox templateId;
    private EditBox lodPack;
    private EditBox anchorX;
    private EditBox anchorY;
    private EditBox anchorZ;
    private EditBox boundaryWidth;
    private EditBox boundaryDepth;
    private EditBox boundaryHeight;
    private EditBox boundaryColor;
    private EditBox capacity;
    private EditBox movementScale;
    private EditBox blurRadius;
    private EditBox transitionTicks;
    private EditBox dayTime;
    private EditBox cycleTicks;

    public StageTemplateEditorScreen() {
        super(Component.literal("Dynamic Stage Editor"));
    }

    @Override
    protected void init() {
        if (draft == null) {
            draft = initialDraft();
        }
        buildWidgets();
        DynamicStageNetwork.requestTemplates();
    }

    private void buildWidgets() {
        clearWidgets();
        labels.clear();
        editBoxes.clear();

        int panelWidth = Math.min(520, width - 20);
        int left = (width - panelWidth) / 2;
        int top = 24;
        int navWidth = Math.max(100, panelWidth - 164);
        templateId = field(left, top, navWidth, draft.id, 128);
        label("Template", left, top - 10);
        addButton(left + navWidth + 4, top, 38, "<", button -> selectTemplate(-1));
        addButton(left + navWidth + 44, top, 38, ">", button -> selectTemplate(1));
        addButton(left + navWidth + 84, top, 76, "Refresh", button -> DynamicStageNetwork.requestTemplates());

        int tabsY = top + 25;
        int tabWidth = panelWidth / 3;
        addButton(left, tabsY, tabWidth - 2, "Stage", button -> switchTab(Tab.STAGE));
        addButton(left + tabWidth, tabsY, tabWidth - 2, "Backdrop", button -> switchTab(Tab.BACKDROP));
        addButton(left + tabWidth * 2, tabsY, panelWidth - tabWidth * 2, "Time", button -> switchTab(Tab.TIME));

        int contentY = tabsY + 29;
        switch (tab) {
            case STAGE -> buildStageTab(left, contentY, panelWidth);
            case BACKDROP -> buildBackdropTab(left, contentY, panelWidth);
            case TIME -> buildTimeTab(left, contentY, panelWidth);
        }
        buildActions(left, panelWidth);
    }

    private void buildStageTab(int left, int top, int panelWidth) {
        lodPack = labeledField("LOD package", left, top, panelWidth, draft.lodPack, 256);
        int y = top + ROW_HEIGHT;
        int third = (panelWidth - 8) / 3;
        anchorX = labeledCompact("Anchor X", left, y, third, Integer.toString(draft.anchor.getX()));
        anchorY = labeledCompact("Y", left + third + 4, y, third, Integer.toString(draft.anchor.getY()));
        anchorZ = labeledCompact("Z", left + (third + 4) * 2, y,
                panelWidth - (third + 4) * 2, Integer.toString(draft.anchor.getZ()));
        y += ROW_HEIGHT;
        boundaryWidth = labeledCompact("Width", left, y, third, Integer.toString(draft.boundary.width()));
        boundaryDepth = labeledCompact("Depth", left + third + 4, y, third,
                Integer.toString(draft.boundary.depth()));
        boundaryHeight = labeledCompact("Height", left + (third + 4) * 2, y,
                panelWidth - (third + 4) * 2, Integer.toString(draft.boundary.height()));
        y += ROW_HEIGHT;
        int half = (panelWidth - 4) / 2;
        boundaryColor = labeledCompact("Boundary RGB", left, y, half,
                String.format(Locale.ROOT, "%06X", draft.boundary.color()));
        capacity = labeledCompact("Capacity", left + half + 4, y, panelWidth - half - 4,
                Integer.toString(draft.capacity));
        y += ROW_HEIGHT;
        addButton(left, y, half, "Instances: " + lower(draft.instanceMode), button -> {
            draft.instanceMode = draft.instanceMode == StageTemplate.InstanceMode.SHARED
                    ? StageTemplate.InstanceMode.PARALLEL : StageTemplate.InstanceMode.SHARED;
            button.setMessage(Component.literal("Instances: " + lower(draft.instanceMode)));
        });
        addButton(left + half + 4, y, panelWidth - half - 4, "Reset: " + lower(draft.resetPolicy), button -> {
            draft.resetPolicy = draft.resetPolicy == StageTemplate.ResetPolicy.ON_CREATE
                    ? StageTemplate.ResetPolicy.MANUAL : StageTemplate.ResetPolicy.ON_CREATE;
            button.setMessage(Component.literal("Reset: " + lower(draft.resetPolicy)));
        });
        y += ROW_HEIGHT;
        addButton(left, y, panelWidth, "Use current native LOD", button -> prepareCurrentLod());
    }

    private void buildBackdropTab(int left, int top, int panelWidth) {
        int half = (panelWidth - 4) / 2;
        addButton(left, top, half, "Player movement: " + onOff(draft.followPlayer), button -> {
            draft.followPlayer = !draft.followPlayer;
            button.setMessage(Component.literal("Player movement: " + onOff(draft.followPlayer)));
        });
        addButton(left + half + 4, top, panelWidth - half - 4, "LOD visible: " + onOff(draft.lodVisible),
                button -> {
                    draft.lodVisible = !draft.lodVisible;
                    button.setMessage(Component.literal("LOD visible: " + onOff(draft.lodVisible)));
                });
        int y = top + ROW_HEIGHT;
        movementScale = labeledCompact("Movement scale", left, y, half,
                Float.toString(draft.movementScale));
        blurRadius = labeledCompact("Persistent blur", left + half + 4, y, panelWidth - half - 4,
                Float.toString(draft.blurRadius));
        y += ROW_HEIGHT;
        addButton(left, y, half, "Transition: " + lower(draft.transition), button -> {
            draft.transition = switch (draft.transition) {
                case INSTANT -> StageClientScene.Transition.FADE;
                case FADE -> StageClientScene.Transition.BLUR;
                case BLUR -> StageClientScene.Transition.INSTANT;
            };
            button.setMessage(Component.literal("Transition: " + lower(draft.transition)));
            if (transitionTicks != null && draft.transition == StageClientScene.Transition.INSTANT) {
                transitionTicks.setValue("0");
            }
        });
        transitionTicks = labeledCompact("Transition ticks", left + half + 4, y,
                panelWidth - half - 4, Integer.toString(draft.transitionTicks));
        y += ROW_HEIGHT;
        addButton(left, y, panelWidth, "Sky: " + lower(draft.skyMode), button -> {
            draft.skyMode = switch (draft.skyMode) {
                case OVERWORLD -> StageClientScene.SkyMode.END;
                case END -> StageClientScene.SkyMode.OFF;
                case OFF -> StageClientScene.SkyMode.OVERWORLD;
            };
            button.setMessage(Component.literal("Sky: " + lower(draft.skyMode)));
        });
    }

    private void buildTimeTab(int left, int top, int panelWidth) {
        addButton(left, top, panelWidth, "Time: " + lower(draft.timeMode), button -> {
            draft.timeMode = switch (draft.timeMode) {
                case FOLLOW -> StageClientScene.TimeMode.FIXED;
                case FIXED -> StageClientScene.TimeMode.CYCLE;
                case CYCLE -> StageClientScene.TimeMode.FOLLOW;
            };
            button.setMessage(Component.literal("Time: " + lower(draft.timeMode)));
        });
        int y = top + ROW_HEIGHT;
        int half = (panelWidth - 4) / 2;
        dayTime = labeledCompact("Base day time", left, y, half, Long.toString(draft.baseDayTime));
        cycleTicks = labeledCompact("Cycle ticks", left + half + 4, y, panelWidth - half - 4,
                Long.toString(draft.cycleTicks));
    }

    private void buildActions(int left, int panelWidth) {
        int y = height - 26;
        int gap = 3;
        int buttonWidth = (panelWidth - gap * 3) / 4;
        addButton(left, y, buttonWidth, "Save", button -> submit(StageTemplatePackets.Action.SAVE));
        Button capture = addButton(left + buttonWidth + gap, y, buttonWidth, "Capture",
                button -> submit(StageTemplatePackets.Action.CAPTURE_ACTIVE));
        capture.active = ClientStageSession.active() != null;
        Button enter = addButton(left + (buttonWidth + gap) * 2, y, buttonWidth, "Save & Enter",
                button -> submit(StageTemplatePackets.Action.SAVE_AND_START));
        enter.active = ClientStageSession.active() == null;
        addButton(left + (buttonWidth + gap) * 3, y,
                panelWidth - (buttonWidth + gap) * 3, "Close", button -> onClose());
    }

    private void switchTab(Tab next) {
        if (captureVisible()) {
            tab = next;
            buildWidgets();
        }
    }

    private void selectTemplate(int direction) {
        List<StageTemplateSummary> templates = StageTemplateEditorState.templates();
        if (templates.isEmpty()) {
            status = "No templates";
            return;
        }
        selectedTemplate = Math.floorMod(selectedTemplate + direction, templates.size());
        draft = Draft.from(templates.get(selectedTemplate));
        status = (selectedTemplate + 1) + " / " + templates.size();
        buildWidgets();
    }

    private void prepareCurrentLod() {
        if (!captureVisible()) {
            return;
        }
        status = "Preparing current LOD...";
        StageLodClientCommands.prepareCurrentLod().whenComplete((pack, error) ->
                Minecraft.getInstance().execute(() -> {
                    if (minecraft == null || minecraft.screen != this) {
                        return;
                    }
                    if (error != null) {
                        status = "LOD preparation failed: " + rootMessage(error);
                        return;
                    }
                    if (pack == null) {
                        status = "No native LOD cache is open";
                        return;
                    }
                    draft.lodPack = pack.toString();
                    if (minecraft.player != null) {
                        draft.anchor = minecraft.player.blockPosition();
                    }
                    status = "Prepared " + pack;
                    buildWidgets();
                }));
    }

    private void submit(StageTemplatePackets.Action action) {
        if (!captureVisible()) {
            return;
        }
        try {
            StageTemplateSummary summary = draft.toSummary(currentGameTime());
            DynamicStageNetwork.editTemplate(new StageTemplatePackets.EditPacket(action, summary));
            status = action == StageTemplatePackets.Action.CAPTURE_ACTIVE
                    ? "Capturing stage..." : "Saving template...";
            if (action == StageTemplatePackets.Action.SAVE_AND_START) {
                onClose();
            }
        } catch (RuntimeException e) {
            status = e.getMessage();
        }
    }

    private boolean captureVisible() {
        try {
            draft.id = nonBlank(templateId.getValue(), "Template ID");
            switch (tab) {
                case STAGE -> {
                    draft.lodPack = nonBlank(lodPack.getValue(), "LOD package");
                    draft.anchor = new BlockPos(integer(anchorX), integer(anchorY), integer(anchorZ));
                    draft.boundary = new StageBoundary(integer(boundaryWidth), integer(boundaryDepth),
                            integer(boundaryHeight), parseColor(boundaryColor.getValue()));
                    draft.capacity = integer(capacity);
                }
                case BACKDROP -> {
                    draft.movementScale = decimal(movementScale);
                    draft.blurRadius = decimal(blurRadius);
                    draft.transitionTicks = draft.transition == StageClientScene.Transition.INSTANT
                            ? 0 : integer(transitionTicks);
                }
                case TIME -> {
                    draft.baseDayTime = Long.parseLong(dayTime.getValue());
                    draft.cycleTicks = draft.timeMode == StageClientScene.TimeMode.CYCLE
                            ? Long.parseLong(cycleTicks.getValue()) : 0L;
                }
            }
            status = "";
            return true;
        } catch (RuntimeException e) {
            status = "Invalid value: " + e.getMessage();
            return false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        editBoxes.forEach(EditBox::tick);
        long revision = StageTemplateEditorState.revision();
        if (revision != observedRevision) {
            observedRevision = revision;
            if (status.isEmpty() || status.startsWith("Saving") || status.startsWith("Capturing")) {
                status = StageTemplateEditorState.templates().size() + " templates";
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        for (Label label : labels) {
            graphics.drawString(font, label.text, label.x, label.y, 0xA0A0A0, false);
        }
        if (!status.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 38,
                    status.startsWith("Invalid") || status.contains("failed") ? 0xFF6666 : 0xB8B8B8);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox labeledField(String name, int x, int y, int width, String value, int maxLength) {
        int labelWidth = 72;
        label(name, x, y + 5);
        return field(x + labelWidth, y, width - labelWidth, value, maxLength);
    }

    private EditBox labeledCompact(String name, int x, int y, int width, String value) {
        int labelWidth = Math.min(78, Math.max(34, width / 2));
        label(name, x, y + 5);
        return field(x + labelWidth, y, width - labelWidth, value, 32);
    }

    private EditBox field(int x, int y, int width, String value, int maxLength) {
        EditBox box = new EditBox(font, x, y, Math.max(24, width), FIELD_HEIGHT, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value);
        editBoxes.add(box);
        return addRenderableWidget(box);
    }

    private Button addButton(int x, int y, int width, String text, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(text), action)
                .bounds(x, y, Math.max(20, width), 20).build());
    }

    private void label(String value, int x, int y) {
        labels.add(new Label(Component.literal(value), x, y));
    }

    private Draft initialDraft() {
        ClientStageSession.Snapshot active = ClientStageSession.active();
        if (active != null) {
            return Draft.from(new StageTemplateSummary(active.stageId(), active.lodPackId(), active.lodAnchor(),
                    active.boundary(), active.clientScene(), active.capacity(),
                    StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE));
        }
        Minecraft mc = Minecraft.getInstance();
        BlockPos anchor = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        long gameTime = currentGameTime();
        long dayTime = mc.level == null ? 0L : mc.level.getDayTime();
        return Draft.from(new StageTemplateSummary("stage_1", new ResourceLocation("dynamicstage", "none"),
                anchor, StageBoundary.defaults(), StageClientScene.defaults(dayTime, gameTime), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE));
    }

    private long currentGameTime() {
        return minecraft == null || minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    private static int integer(EditBox box) {
        return Integer.parseInt(box.getValue());
    }

    private static float decimal(EditBox box) {
        return Float.parseFloat(box.getValue());
    }

    private static int parseColor(String value) {
        String digits = value.startsWith("#") ? value.substring(1) : value;
        if (!digits.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("boundary color must be six hex digits");
        }
        return Integer.parseInt(digits, 16);
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private enum Tab { STAGE, BACKDROP, TIME }

    private record Label(Component text, int x, int y) {
    }

    private static final class Draft {
        private String id;
        private String lodPack;
        private BlockPos anchor;
        private StageBoundary boundary;
        private int capacity;
        private StageTemplate.InstanceMode instanceMode;
        private StageTemplate.ResetPolicy resetPolicy;
        private boolean followPlayer;
        private float movementScale;
        private boolean lodVisible;
        private float blurRadius;
        private StageClientScene.Transition transition;
        private int transitionTicks;
        private StageClientScene.TimeMode timeMode;
        private long baseDayTime;
        private long cycleTicks;
        private StageClientScene.SkyMode skyMode;

        private static Draft from(StageTemplateSummary summary) {
            Draft draft = new Draft();
            StageClientScene scene = summary.clientScene();
            draft.id = summary.id();
            draft.lodPack = summary.lodPackId().toString();
            draft.anchor = summary.lodAnchor();
            draft.boundary = summary.boundary();
            draft.capacity = summary.capacity();
            draft.instanceMode = summary.instanceMode();
            draft.resetPolicy = summary.resetPolicy();
            draft.followPlayer = scene.followPlayer();
            draft.movementScale = scene.lodMovementScale();
            draft.lodVisible = scene.lodVisible();
            draft.blurRadius = scene.lodBlurRadius();
            draft.transition = scene.lodTransition();
            draft.transitionTicks = scene.lodTransitionTicks();
            draft.timeMode = scene.timeMode();
            draft.baseDayTime = scene.timeBaseDayTime();
            draft.cycleTicks = scene.timeCycleTicks();
            draft.skyMode = scene.skyMode();
            return draft;
        }

        private StageTemplateSummary toSummary(long gameTime) {
            ResourceLocation pack = ResourceLocation.tryParse(lodPack);
            if (pack == null) {
                throw new IllegalArgumentException("invalid LOD package ID");
            }
            long normalizedDayTime = Math.floorMod(baseDayTime, 24_000L);
            StageClientScene scene = new StageClientScene(followPlayer, movementScale, lodVisible, blurRadius,
                    transition, transition == StageClientScene.Transition.INSTANT ? 0 : transitionTicks,
                    gameTime, timeMode, normalizedDayTime, gameTime,
                    timeMode == StageClientScene.TimeMode.CYCLE ? cycleTicks : 0L, skyMode);
            return new StageTemplateSummary(id, pack, anchor, boundary, scene, capacity, instanceMode, resetPolicy);
        }
    }
}
