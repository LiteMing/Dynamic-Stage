package vibe.liteming.dynamicstage.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StageLodClientCommandsTest {

    @Test
    void mergesLocalLodCommandsIntoTheServerRoot() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("dstage")
                .then(LiteralArgumentBuilder.<Object>literal("start")
                        .then(RequiredArgumentBuilder.<Object, String>argument(
                                        "stage", StringArgumentType.string())
                                .then(RequiredArgumentBuilder.<Object, String>argument(
                                        "lod_pack", StringArgumentType.word())))));

        StageLodClientCommands.register(dispatcher);

        var root = dispatcher.getRoot().getChild("dstage");
        var start = root.getChild("start");
        assertNotNull(start);
        var stage = start.getChild("stage");
        assertNotNull(stage);
        assertNotNull(stage.getCommand());
        assertNotNull(stage.getChild("lod_pack"));
        assertNotNull(root.getChild("lod"));
        assertNotNull(root.getChild("editor"));
        assertNotNull(root.getChild("gui"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("link"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("link-relative"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("copy"));
        assertNotNull(root.getChild("lod").getChild("downloads").getChild("status"));
        assertNotNull(root.getChild("lod").getChild("downloads").getChild("on"));
        assertNotNull(root.getChild("lod").getChild("downloads").getChild("off"));
        assertNotNull(root.getChild("lod").getChild("downloads").getChild("max"));
        assertNotNull(root.getChild("lod").getChild("collision").getChild("status"));
        assertNotNull(root.getChild("lod").getChild("collision").getChild("on"));
        assertNotNull(root.getChild("lod").getChild("collision").getChild("off"));
        var optimize = root.getChild("lod").getChild("optimize");
        assertNotNull(optimize.getChild("voxy").getChild("crop"));
        assertNotNull(optimize.getChild("voxy").getChild("radius"));
        assertNotNull(optimize.getChild("dh").getChild("crop"));
        assertNotNull(optimize.getChild("dh").getChild("radius"));
    }

    @Test
    void parsesNamespacedLodPackageIdsInEveryClientCommand() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        StageLodClientCommands.register(dispatcher);

        assertFullyParsed(dispatcher,
                "dstage lod optimize dh radius dev:overworld dynamicstage:trimmed 0 0 512 -64 320");
        assertFullyParsed(dispatcher,
                "dstage lod optimize voxy radius dev:overworld dynamicstage:trimmed 0 0 512 -64 320");
        assertFullyParsed(dispatcher, "dstage lod export dynamicstage:trimmed output.dstlod");
        assertFullyParsed(dispatcher, "dstage lod import link dynamicstage:trimmed D:/lod/source");
    }

    private static void assertFullyParsed(CommandDispatcher<Object> dispatcher, String command) {
        assertFalse(dispatcher.parse(command, new Object()).getReader().canRead(), command);
    }
}
