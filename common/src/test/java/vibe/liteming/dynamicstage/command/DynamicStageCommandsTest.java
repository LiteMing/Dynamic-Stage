package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DynamicStageCommandsTest {
    @Test
    void registersControlledStageEditCommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        DynamicStageCommands.register(dispatcher);

        var edit = dispatcher.getRoot().getChild("dstage").getChild("edit");
        var root = dispatcher.getRoot().getChild("dstage");
        assertNotNull(root.getChild("join"));
        assertNotNull(root.getChild("join").getChild("instance"));
        assertNotNull(root.getChild("exit"));
        assertNotNull(edit);
        assertNotNull(edit.getChild("status"));
        assertNotNull(edit.getChild("on"));
        assertNotNull(edit.getChild("off"));
    }
}
