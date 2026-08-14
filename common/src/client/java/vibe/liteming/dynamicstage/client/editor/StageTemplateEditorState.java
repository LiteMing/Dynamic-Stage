package vibe.liteming.dynamicstage.client.editor;

import vibe.liteming.dynamicstage.template.StageTemplateSummary;

import java.util.List;

/** Latest server-authoritative template summaries for the open editor. */
public final class StageTemplateEditorState {
    private static volatile List<StageTemplateSummary> templates = List.of();
    private static volatile long revision;

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

    public static void clear() {
        templates = List.of();
        revision++;
    }
}
