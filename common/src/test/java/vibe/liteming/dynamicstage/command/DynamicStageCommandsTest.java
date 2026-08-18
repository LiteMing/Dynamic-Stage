package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamicStageCommandsTest {
    @Test
    void keepsStageEditingOutOfTheServerCommandTree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        DynamicStageCommands.register(dispatcher);

        var root = dispatcher.getRoot().getChild("dstage");
        assertNotNull(root.getChild("join"));
        assertNotNull(root.getChild("join").getChild("instance"));
        assertNotNull(root.getChild("invite"));
        assertNotNull(root.getChild("guide"));
        assertNotNull(root.getChild("exit"));
        assertNull(root.getChild("edit"));
    }
}
