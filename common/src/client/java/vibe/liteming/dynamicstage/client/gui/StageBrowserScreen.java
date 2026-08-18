package vibe.liteming.dynamicstage.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import vibe.liteming.dynamicstage.client.config.StageClientConfig;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageBrowserPacket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Player-facing stage instance browser and local rendering settings. */
public final class StageBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 25;
    private static final int FIELD_HEIGHT = 20;
    private static final String PREFIX = "screen.dynamicstage.browser.";

    private final List<Label> labels = new ArrayList<>();
    private final List<EditBox> editBoxes = new ArrayList<>();
    private Tab tab = Tab.INSTANCES;
    private int selectedInstance = -1;
    private long observedRevision = -1L;
    private Component status = Component.empty();
    private boolean statusError;

    private StageClientConfig.BoundaryDisplay clientBoundary;
    private boolean clientAllowServerLodDownloads;
    private int clientMaxServerLodDownloadMib;
    private boolean clientExperimentalVoxyCollision;
    private EditBox clientBoundaryDistance;
    private EditBox clientBoundaryOpacity;
    private EditBox clientBoundaryColor;
    private EditBox clientMaxServerLodMib;

    private int instanceHoverX;
    private int instanceHoverY;
    private int instanceHoverWidth;
    private int instanceHoverHeight;
    private Component instanceHover = Component.empty();

    public StageBrowserScreen() {
        super(text("title"));
    }

    @Override
    protected void init() {
        if (clientBoundary == null) {
            clientBoundary = StageClientConfig.boundary();
            clientAllowServerLodDownloads = StageClientConfig.allowServerLodDownloads();
            clientMaxServerLodDownloadMib = StageClientConfig.maxServerLodDownloadMib();
            clientExperimentalVoxyCollision = StageClientConfig.experimentalVoxyCollision();
        }
        buildWidgets();
        DynamicStageNetwork.requestBrowser(StageBrowserPacket.Request.refresh());
    }

    private void buildWidgets() {
        clearWidgets();
        labels.clear();
        editBoxes.clear();
        instanceHover = Component.empty();

        int panelWidth = Math.min(480, width - 20);
        int left = (width - panelWidth) / 2;
        int top = 30;
        int half = panelWidth / 2;
        addButton(left, top, half - 2, text("tab.instances"), button -> switchTab(Tab.INSTANCES));
        addButton(left + half, top, panelWidth - half, text("tab.client"), button -> switchTab(Tab.CLIENT));
        int contentY = top + 31;
        if (tab == Tab.INSTANCES) {
            buildInstances(left, contentY, panelWidth);
        } else {
            buildClientSettings(left, contentY, panelWidth);
        }
        buildActions(left, panelWidth);
    }

    private void buildInstances(int left, int top, int panelWidth) {
        List<StageBrowserPacket.Instance> instances = StageBrowserState.state().instances();
        if (instances.isEmpty()) {
            selectedInstance = -1;
            label(text("status.no_instances"), left, top + 6);
            return;
        }
        selectedInstance = selectedInstance < 0 ? 0 : Math.floorMod(selectedInstance, instances.size());
        StageBrowserPacket.Instance instance = instances.get(selectedInstance);
        int navWidth = 38;
        addButton(left, top, navWidth, Component.literal("<"), button -> selectInstance(-1));
        addButton(left + panelWidth - navWidth, top, navWidth, Component.literal(">"),
                button -> selectInstance(1));
        label(text("status.instance_index", selectedInstance + 1, instances.size()),
                left + navWidth + 8, top + 6);

        int y = top + ROW_HEIGHT + 5;
        label(Component.literal(instance.label()), left, y);
        instanceHoverX = left;
        instanceHoverY = y - 2;
        instanceHoverWidth = Math.max(100, font.width(instance.label()) + 8);
        instanceHoverHeight = 14;
        instanceHover = text("instance.uuid", instance.instanceId());
        y += 20;
        label(text("instance.template", instance.stageId()), left, y);
        y += 18;
        label(text("instance.members", instance.members(), instance.capacity()), left, y);
        y += 25;

        boolean full = instance.members() >= instance.capacity();
        Component availability = full ? text("status.full")
                : instance.available() ? text("status.available") : text("status.unavailable");
        label(availability, left, y + 5);
        Button join = addButton(left + panelWidth - 120, y, 120, text("action.join"), button -> {
            DynamicStageNetwork.requestBrowser(new StageBrowserPacket.Request(
                    StageBrowserPacket.Action.JOIN, instance.instanceId()));
            setStatus(text("status.preparing"), false);
        });
        join.active = ClientStageSession.active() == null && !full && instance.available();
    }

    private void buildClientSettings(int left, int top, int panelWidth) {
        int half = (panelWidth - 4) / 2;
        clientBoundaryDistance = labeledCompact("boundary_distance", left, top, half,
                Double.toString(clientBoundary.visibleDistance()));
        clientBoundaryOpacity = labeledCompact("boundary_opacity", left + half + 4, top,
                panelWidth - half - 4, Float.toString(clientBoundary.opacity()));
        clientBoundaryColor = labeledField("boundary_color", left, top + ROW_HEIGHT, panelWidth,
                clientBoundary.colorSetting(), 16);
        int y = top + ROW_HEIGHT * 2;
        addButton(left, y, half, text("setting.server_lod_downloads",
                toggle(clientAllowServerLodDownloads)), button -> {
            clientAllowServerLodDownloads = !clientAllowServerLodDownloads;
            button.setMessage(text("setting.server_lod_downloads", toggle(clientAllowServerLodDownloads)));
        });
        clientMaxServerLodMib = labeledCompact("server_lod_max_mib", left + half + 4, y,
                panelWidth - half - 4, Integer.toString(clientMaxServerLodDownloadMib));
        y += ROW_HEIGHT;
        addButton(left, y, panelWidth, text("setting.experimental_voxy_collision",
                toggle(clientExperimentalVoxyCollision)), button -> {
            clientExperimentalVoxyCollision = !clientExperimentalVoxyCollision;
            button.setMessage(text("setting.experimental_voxy_collision",
                    toggle(clientExperimentalVoxyCollision)));
        });
    }

    private void buildActions(int left, int panelWidth) {
        int y = height - 26;
        int gap = 4;
        int half = (panelWidth - gap) / 2;
        if (tab == Tab.CLIENT) {
            addButton(left, y, half, text("action.save_client"), button -> saveClientConfig());
        } else {
            addButton(left, y, half, text("action.refresh"), button ->
                    DynamicStageNetwork.requestBrowser(StageBrowserPacket.Request.refresh()));
        }
        addButton(left + half + gap, y, panelWidth - half - gap, text("action.close"), button -> onClose());
    }

    private void switchTab(Tab next) {
        tab = next;
        buildWidgets();
    }

    private void selectInstance(int direction) {
        int size = StageBrowserState.state().instances().size();
        if (size == 0) {
            selectedInstance = -1;
            return;
        }
        selectedInstance = Math.floorMod(selectedInstance + direction, size);
        buildWidgets();
    }

    private void saveClientConfig() {
        try {
            clientBoundary = StageClientConfig.createBoundaryDisplay(
                    Double.parseDouble(clientBoundaryDistance.getValue()),
                    Float.parseFloat(clientBoundaryOpacity.getValue()),
                    clientBoundaryColor.getValue().trim());
            clientMaxServerLodDownloadMib = Integer.parseInt(clientMaxServerLodMib.getValue());
            StageClientConfig.save(clientBoundary, clientAllowServerLodDownloads,
                    clientMaxServerLodDownloadMib, clientExperimentalVoxyCollision);
            setStatus(text("status.client_saved"), false);
        } catch (IOException | RuntimeException e) {
            setStatus(text("status.invalid_value", e.getMessage()), true);
        }
    }

    @Override
    public void tick() {
        editBoxes.forEach(EditBox::tick);
        long revision = StageBrowserState.revision();
        if (revision != observedRevision) {
            observedRevision = revision;
            StageBrowserPacket.State state = StageBrowserState.state();
            if (selectedInstance >= state.instances().size()) {
                selectedInstance = state.instances().isEmpty() ? -1 : state.instances().size() - 1;
            }
            if (!state.message().isEmpty()) {
                setStatus(text(state.message()), state.error());
            }
            if (tab == Tab.INSTANCES) {
                buildWidgets();
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        for (Label label : labels) {
            graphics.drawString(font, label.text(), label.x(), label.y(), 0xD0D0D0, false);
        }
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 39,
                    statusError ? 0xFF6666 : 0xB8B8B8);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!instanceHover.getString().isEmpty() && mouseX >= instanceHoverX
                && mouseX < instanceHoverX + instanceHoverWidth && mouseY >= instanceHoverY
                && mouseY < instanceHoverY + instanceHoverHeight) {
            graphics.renderTooltip(font, instanceHover, mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox labeledField(String key, int x, int y, int width, String value, int maxLength) {
        Component name = text("field." + key);
        int labelWidth = 82;
        label(name, x, y + 5);
        return field(x + labelWidth, y, width - labelWidth, value, maxLength, name);
    }

    private EditBox labeledCompact(String key, int x, int y, int width, String value) {
        Component name = text("field." + key);
        int labelWidth = Math.min(86, Math.max(44, width / 2));
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

    private void setStatus(Component value, boolean error) {
        status = value;
        statusError = error;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    private static Component toggle(boolean enabled) {
        return text(enabled ? "value.on" : "value.off");
    }

    private enum Tab { INSTANCES, CLIENT }

    private record Label(Component text, int x, int y) {
    }
}
