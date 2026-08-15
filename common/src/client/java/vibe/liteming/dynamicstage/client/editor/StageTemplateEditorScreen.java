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
    private Component status = Component.empty();
    private boolean statusError;
    private boolean statusPending;
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
    private EditBox dhNearFadeScale;
    private EditBox blurRadius;
    private EditBox transitionTicks;
    private EditBox flightName;
    private EditBox dayTime;
    private EditBox cycleTicks;

    public StageTemplateEditorScreen() {
        super(text("title"));
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
        Component templateLabel = text("field.template");
        templateId = field(left, top, navWidth, draft.id, 128, templateLabel);
        label(templateLabel, left, top - 10);
        addButton(left + navWidth + 4, top, 38, Component.literal("<"), button -> selectTemplate(-1));
        addButton(left + navWidth + 44, top, 38, Component.literal(">"), button -> selectTemplate(1));
        addButton(left + navWidth + 84, top, 76, text("action.refresh"),
                button -> DynamicStageNetwork.requestTemplates());

        int tabsY = top + 25;
        int tabWidth = panelWidth / 3;
        addButton(left, tabsY, tabWidth - 2, text("tab.stage"), button -> switchTab(Tab.STAGE));
        addButton(left + tabWidth, tabsY, tabWidth - 2, text("tab.backdrop"),
                button -> switchTab(Tab.BACKDROP));
        addButton(left + tabWidth * 2, tabsY, panelWidth - tabWidth * 2, text("tab.time"),
                button -> switchTab(Tab.TIME));

        int contentY = tabsY + 29;
        switch (tab) {
            case STAGE -> buildStageTab(left, contentY, panelWidth);
            case BACKDROP -> buildBackdropTab(left, contentY, panelWidth);
            case TIME -> buildTimeTab(left, contentY, panelWidth);
        }
        buildActions(left, panelWidth);
    }

    private void buildStageTab(int left, int top, int panelWidth) {
        lodPack = labeledField("lod_package", left, top, panelWidth, draft.lodPack, 256);
        int y = top + ROW_HEIGHT;
        int third = (panelWidth - 8) / 3;
        anchorX = labeledCompact("anchor_x", left, y, third, Integer.toString(draft.anchor.getX()));
        anchorY = labeledCompact("anchor_y", left + third + 4, y, third, Integer.toString(draft.anchor.getY()));
        anchorZ = labeledCompact("anchor_z", left + (third + 4) * 2, y,
                panelWidth - (third + 4) * 2, Integer.toString(draft.anchor.getZ()));
        y += ROW_HEIGHT;
        boundaryWidth = labeledCompact("width", left, y, third, Integer.toString(draft.boundary.width()));
        boundaryDepth = labeledCompact("depth", left + third + 4, y, third,
                Integer.toString(draft.boundary.depth()));
        boundaryHeight = labeledCompact("height", left + (third + 4) * 2, y,
                panelWidth - (third + 4) * 2, Integer.toString(draft.boundary.height()));
        y += ROW_HEIGHT;
        int half = (panelWidth - 4) / 2;
        boundaryColor = labeledCompact("boundary_rgb", left, y, half,
                String.format(Locale.ROOT, "%06X", draft.boundary.color()));
        capacity = labeledCompact("capacity", left + half + 4, y, panelWidth - half - 4,
                Integer.toString(draft.capacity));
        y += ROW_HEIGHT;
        addButton(left, y, half, text("setting.instances", value(draft.instanceMode)), button -> {
            draft.instanceMode = draft.instanceMode == StageTemplate.InstanceMode.SHARED
                    ? StageTemplate.InstanceMode.PARALLEL : StageTemplate.InstanceMode.SHARED;
            button.setMessage(text("setting.instances", value(draft.instanceMode)));
        });
        addButton(left + half + 4, y, panelWidth - half - 4,
                text("setting.reset", value(draft.resetPolicy)), button -> {
            draft.resetPolicy = draft.resetPolicy == StageTemplate.ResetPolicy.ON_CREATE
                    ? StageTemplate.ResetPolicy.MANUAL : StageTemplate.ResetPolicy.ON_CREATE;
            button.setMessage(text("setting.reset", value(draft.resetPolicy)));
        });
        y += ROW_HEIGHT;
        addButton(left, y, panelWidth, text("action.use_current_lod"), button -> prepareCurrentLod());
    }

    private void buildBackdropTab(int left, int top, int panelWidth) {
        int half = (panelWidth - 4) / 2;
        addButton(left, top, half, text("setting.player_movement", toggle(draft.followPlayer)), button -> {
            draft.followPlayer = !draft.followPlayer;
            button.setMessage(text("setting.player_movement", toggle(draft.followPlayer)));
        });
        addButton(left + half + 4, top, panelWidth - half - 4,
                text("setting.lod_visible", toggle(draft.lodVisible)),
                button -> {
                    draft.lodVisible = !draft.lodVisible;
                    button.setMessage(text("setting.lod_visible", toggle(draft.lodVisible)));
                });
        int y = top + ROW_HEIGHT;
        movementScale = labeledCompact("movement_scale", left, y, half,
                Float.toString(draft.movementScale));
        dhNearFadeScale = labeledCompact("dh_near_fade", left + half + 4, y, panelWidth - half - 4,
                Float.toString(draft.dhNearFadeScale));
        y += ROW_HEIGHT;
        addButton(left, y, half, text("setting.voxy_near_culling", toggle(draft.voxyNearCulling)), button -> {
            draft.voxyNearCulling = !draft.voxyNearCulling;
            button.setMessage(text("setting.voxy_near_culling", toggle(draft.voxyNearCulling)));
        });
        blurRadius = labeledCompact("persistent_blur", left + half + 4, y, panelWidth - half - 4,
                Float.toString(draft.blurRadius));
        y += ROW_HEIGHT;
        addButton(left, y, half, text("setting.transition", value(draft.transition)), button -> {
            draft.transition = switch (draft.transition) {
                case INSTANT -> StageClientScene.Transition.FADE;
                case FADE -> StageClientScene.Transition.BLUR;
                case BLUR -> StageClientScene.Transition.INSTANT;
            };
            button.setMessage(text("setting.transition", value(draft.transition)));
            if (transitionTicks != null && draft.transition == StageClientScene.Transition.INSTANT) {
                transitionTicks.setValue("0");
            }
        });
        transitionTicks = labeledCompact("transition_ticks", left + half + 4, y,
                panelWidth - half - 4, Integer.toString(draft.transitionTicks));
        y += ROW_HEIGHT;
        addButton(left, y, half, text("setting.sky", value(draft.skyMode)), button -> {
            draft.skyMode = switch (draft.skyMode) {
                case OVERWORLD -> StageClientScene.SkyMode.END;
                case END -> StageClientScene.SkyMode.OFF;
                case OFF -> StageClientScene.SkyMode.OVERWORLD;
            };
            button.setMessage(text("setting.sky", value(draft.skyMode)));
        });
        addButton(left + half + 4, y, panelWidth - half - 4, text("action.clear_flight"),
                button -> submit(StageTemplatePackets.Action.CLEAR_FLIGHT));
        y += ROW_HEIGHT;
        int flightFieldWidth = (panelWidth - 4) * 3 / 5;
        flightName = labeledField("flight_name", left, y, flightFieldWidth, draft.flightName, 64);
        addButton(left + flightFieldWidth + 4, y, panelWidth - flightFieldWidth - 4,
                text("action.bind_selected_flight"),
                button -> submit(StageTemplatePackets.Action.USE_CONFIGURED_FLIGHT));
    }

    private void buildTimeTab(int left, int top, int panelWidth) {
        addButton(left, top, panelWidth, text("setting.time", value(draft.timeMode)), button -> {
            draft.timeMode = switch (draft.timeMode) {
                case FOLLOW -> StageClientScene.TimeMode.FIXED;
                case FIXED -> StageClientScene.TimeMode.CYCLE;
                case CYCLE -> StageClientScene.TimeMode.FOLLOW;
            };
            button.setMessage(text("setting.time", value(draft.timeMode)));
        });
        int y = top + ROW_HEIGHT;
        int half = (panelWidth - 4) / 2;
        dayTime = labeledCompact("base_day_time", left, y, half, Long.toString(draft.baseDayTime));
        cycleTicks = labeledCompact("cycle_ticks", left + half + 4, y, panelWidth - half - 4,
                Long.toString(draft.cycleTicks));
    }

    private void buildActions(int left, int panelWidth) {
        int y = height - 26;
        int gap = 3;
        int buttonWidth = (panelWidth - gap * 3) / 4;
        addButton(left, y, buttonWidth, text("action.save"), button -> submit(StageTemplatePackets.Action.SAVE));
        Button capture = addButton(left + buttonWidth + gap, y, buttonWidth, text("action.capture"),
                button -> submit(StageTemplatePackets.Action.CAPTURE_ACTIVE));
        capture.active = ClientStageSession.active() != null;
        Button enter = addButton(left + (buttonWidth + gap) * 2, y, buttonWidth, text("action.save_and_enter"),
                button -> submit(StageTemplatePackets.Action.SAVE_AND_START));
        enter.active = ClientStageSession.active() == null;
        addButton(left + (buttonWidth + gap) * 3, y,
                panelWidth - (buttonWidth + gap) * 3, text("action.close"), button -> onClose());
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
            setStatus("status.no_templates");
            return;
        }
        selectedTemplate = Math.floorMod(selectedTemplate + direction, templates.size());
        draft = Draft.from(templates.get(selectedTemplate));
        setStatus("status.template_index", selectedTemplate + 1, templates.size());
        buildWidgets();
    }

    private void prepareCurrentLod() {
        if (!captureVisible()) {
            return;
        }
        setStatus("status.preparing_lod");
        StageLodClientCommands.prepareCurrentLod().whenComplete((pack, error) ->
                Minecraft.getInstance().execute(() -> {
                    if (minecraft == null || minecraft.screen != this) {
                        return;
                    }
                    if (error != null) {
                        setErrorStatus("status.lod_preparation_failed", rootMessage(error));
                        return;
                    }
                    if (pack == null) {
                        setErrorStatus("status.no_native_lod");
                        return;
                    }
                    draft.lodPack = pack.toString();
                    if (minecraft.player != null) {
                        draft.anchor = minecraft.player.blockPosition();
                    }
                    setStatus("status.prepared_lod", pack);
                    buildWidgets();
                }));
    }

    private void submit(StageTemplatePackets.Action action) {
        if (!captureVisible()) {
            return;
        }
        try {
            if (action == StageTemplatePackets.Action.USE_CONFIGURED_FLIGHT && draft.flightName.isEmpty()) {
                throw new IllegalArgumentException(text("validation.empty", text("field.flight_name")).getString());
            }
            StageTemplateSummary summary = draft.toSummary(currentGameTime());
            DynamicStageNetwork.editTemplate(new StageTemplatePackets.EditPacket(action, summary));
            setPendingStatus(action == StageTemplatePackets.Action.CAPTURE_ACTIVE
                    ? "status.capturing_stage"
                    : action == StageTemplatePackets.Action.USE_CONFIGURED_FLIGHT
                    ? "status.binding_flight"
                    : action == StageTemplatePackets.Action.CLEAR_FLIGHT
                    ? "status.clearing_flight" : "status.saving_template");
            if (action == StageTemplatePackets.Action.SAVE_AND_START) {
                onClose();
            }
        } catch (RuntimeException e) {
            setErrorStatus("status.operation_failed", rootMessage(e));
        }
    }

    private boolean captureVisible() {
        try {
            draft.id = nonBlank(templateId.getValue(), text("field.template").getString());
            switch (tab) {
                case STAGE -> {
                    draft.lodPack = nonBlank(lodPack.getValue(), text("field.lod_package").getString());
                    draft.anchor = new BlockPos(integer(anchorX), integer(anchorY), integer(anchorZ));
                    draft.boundary = new StageBoundary(integer(boundaryWidth), integer(boundaryDepth),
                            integer(boundaryHeight), parseColor(boundaryColor.getValue()));
                    draft.capacity = integer(capacity);
                }
                case BACKDROP -> {
                    draft.movementScale = decimal(movementScale);
                    draft.dhNearFadeScale = decimal(dhNearFadeScale);
                    draft.blurRadius = decimal(blurRadius);
                    draft.transitionTicks = draft.transition == StageClientScene.Transition.INSTANT
                            ? 0 : integer(transitionTicks);
                    draft.flightName = flightName.getValue().trim();
                }
                case TIME -> {
                    draft.baseDayTime = Long.parseLong(dayTime.getValue());
                    draft.cycleTicks = draft.timeMode == StageClientScene.TimeMode.CYCLE
                            ? Long.parseLong(cycleTicks.getValue()) : 0L;
                }
            }
            clearStatus();
            return true;
        } catch (RuntimeException e) {
            setErrorStatus("status.invalid_value", rootMessage(e));
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
            if (status.getString().isEmpty() || statusPending) {
                setStatus("status.template_count", StageTemplateEditorState.templates().size());
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
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 38,
                    statusError ? 0xFF6666 : 0xB8B8B8);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox labeledField(String key, int x, int y, int width, String value, int maxLength) {
        Component name = text("field." + key);
        int labelWidth = 72;
        label(name, x, y + 5);
        return field(x + labelWidth, y, width - labelWidth, value, maxLength, name);
    }

    private EditBox labeledCompact(String key, int x, int y, int width, String value) {
        Component name = text("field." + key);
        int labelWidth = Math.min(78, Math.max(34, width / 2));
        label(name, x, y + 5);
        return field(x + labelWidth, y, width - labelWidth, value, 32, name);
    }

    private EditBox field(int x, int y, int width, String value, int maxLength, Component narration) {
        EditBox box = new EditBox(font, x, y, Math.max(24, width), FIELD_HEIGHT, narration);
        box.setMaxLength(maxLength);
        box.setValue(value);
        editBoxes.add(box);
        return addRenderableWidget(box);
    }

    private Button addButton(int x, int y, int width, Component message, Button.OnPress action) {
        return addRenderableWidget(Button.builder(message, action)
                .bounds(x, y, Math.max(20, width), 20).build());
    }

    private void label(Component value, int x, int y) {
        labels.add(new Label(value, x, y));
    }

    private Draft initialDraft() {
        ClientStageSession.Snapshot active = ClientStageSession.active();
        if (active != null) {
            return Draft.from(new StageTemplateSummary(active.stageId(), active.lodPackId(), active.lodAnchor(),
                    active.boundary(), active.clientScene(), active.capacity(),
                    StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE, ""));
        }
        Minecraft mc = Minecraft.getInstance();
        BlockPos anchor = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        long gameTime = currentGameTime();
        long dayTime = mc.level == null ? 0L : mc.level.getDayTime();
        return Draft.from(new StageTemplateSummary("stage_1", new ResourceLocation("dynamicstage", "none"),
                anchor, StageBoundary.defaults(), StageClientScene.defaults(dayTime, gameTime), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE, ""));
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
            throw new IllegalArgumentException(text("validation.boundary_color").getString());
        }
        return Integer.parseInt(digits, 16);
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(text("validation.empty", name).getString());
        }
        return value;
    }

    private void setStatus(String key, Object... args) {
        setStatus(text(key, args), false, false);
    }

    private void setPendingStatus(String key, Object... args) {
        setStatus(text(key, args), false, true);
    }

    private void setErrorStatus(String key, Object... args) {
        setStatus(text(key, args), true, false);
    }

    private void setStatus(Component message, boolean error, boolean pending) {
        status = message;
        statusError = error;
        statusPending = pending;
    }

    private void clearStatus() {
        setStatus(Component.empty(), false, false);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.dynamicstage.editor." + key, args);
    }

    private static Component toggle(boolean enabled) {
        return text(enabled ? "value.on" : "value.off");
    }

    private static Component value(Enum<?> value) {
        return text("value." + value.name().toLowerCase(Locale.ROOT));
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
        private float dhNearFadeScale;
        private boolean voxyNearCulling;
        private boolean lodVisible;
        private float blurRadius;
        private StageClientScene.Transition transition;
        private int transitionTicks;
        private StageClientScene.TimeMode timeMode;
        private long baseDayTime;
        private long cycleTicks;
        private StageClientScene.SkyMode skyMode;
        private String flightName;

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
            draft.dhNearFadeScale = scene.dhNearFadeScale();
            draft.voxyNearCulling = scene.voxyNearCulling();
            draft.lodVisible = scene.lodVisible();
            draft.blurRadius = scene.lodBlurRadius();
            draft.transition = scene.lodTransition();
            draft.transitionTicks = scene.lodTransitionTicks();
            draft.timeMode = scene.timeMode();
            draft.baseDayTime = scene.timeBaseDayTime();
            draft.cycleTicks = scene.timeCycleTicks();
            draft.skyMode = scene.skyMode();
            draft.flightName = summary.flightName();
            return draft;
        }

        private StageTemplateSummary toSummary(long gameTime) {
            ResourceLocation pack = ResourceLocation.tryParse(lodPack);
            if (pack == null) {
                throw new IllegalArgumentException(text("validation.invalid_lod_package").getString());
            }
            long normalizedDayTime = Math.floorMod(baseDayTime, 24_000L);
            StageClientScene scene = new StageClientScene(followPlayer, movementScale, dhNearFadeScale, voxyNearCulling, lodVisible, blurRadius,
                    transition, transition == StageClientScene.Transition.INSTANT ? 0 : transitionTicks,
                    gameTime, timeMode, normalizedDayTime, gameTime,
                    timeMode == StageClientScene.TimeMode.CYCLE ? cycleTicks : 0L, skyMode);
            return new StageTemplateSummary(id, pack, anchor, boundary, scene, capacity, instanceMode, resetPolicy,
                    flightName);
        }
    }
}
