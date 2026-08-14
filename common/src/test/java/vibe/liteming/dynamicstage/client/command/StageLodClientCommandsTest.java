package vibe.liteming.dynamicstage.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

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
        assertNotNull(root.getChild("lod").getChild("import").getChild("link"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("link-relative"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("copy"));
    }
}
