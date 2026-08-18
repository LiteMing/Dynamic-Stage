package vibe.liteming.dynamicstage.client.editor;

import vibe.liteming.dynamicstage.template.StageTemplateSummary;
import vibe.liteming.dynamicstage.network.StageEditorAdminPacket;

import java.util.List;

/** Latest server-authoritative template summaries for the open editor. */
public final class StageTemplateEditorState {
    private static volatile List<StageTemplateSummary> templates = List.of();
    private static volatile long revision;
    private static volatile StageEditorAdminPacket.State admin = new StageEditorAdminPacket.State(
            List.of(), false, false, "", false);
    private static volatile long adminRevision;

    private StageTemplateEditorState() {
    }

    public static void accept(List<StageTemplateSummary> values) {
        templates = List.copyOf(values);
        revision++;
    }

    public static List<StageTemplateSummary> templates() {
        return templates;
    }

    public static long revision() {
        return revision;
    }

    public static void acceptAdmin(StageEditorAdminPacket.State value) {
        admin = value;
        adminRevision++;
    }

    public static StageEditorAdminPacket.State admin() {
        return admin;
    }

    public static long adminRevision() {
        return adminRevision;
    }

    public static void clear() {
        templates = List.of();
        revision++;
        admin = new StageEditorAdminPacket.State(List.of(), false, false, "", false);
        adminRevision++;
    }
}
