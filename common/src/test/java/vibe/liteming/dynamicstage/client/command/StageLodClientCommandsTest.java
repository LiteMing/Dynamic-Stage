package vibe.liteming.dynamicstage.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class StageLodClientCommandsTest {

    @Test
    void mergesLocalLodCommandsIntoTheServerRoot() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("dstage")
                .then(LiteralArgumentBuilder.literal("start")));

        StageLodClientCommands.register(dispatcher);

        var root = dispatcher.getRoot().getChild("dstage");
        assertNotNull(root.getChild("start"));
        assertNotNull(root.getChild("lod"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("link"));
        assertNotNull(root.getChild("lod").getChild("import").getChild("copy"));
    }
}
