package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void flightPlaybackCanEndWithoutATransition() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        DynamicStageCommands.register(dispatcher);

        var flight = dispatcher.getRoot().getChild("dstage")
                .getChild("flight").getChild("play").getChild("flight");
        assertNotNull(flight);
        assertNotNull(flight.getCommand());
    }

    @Test
    void parsesInstantAndTransitionFlightPlayback() {
        var instant = DynamicStageCommands.parseFlightPlay("scarlet");
        assertNotNull(instant);
        assertEquals("scarlet", instant.name());
        assertEquals(StageClientScene.Transition.INSTANT, instant.transition());
        assertEquals(0, instant.ticks());

        var fade = DynamicStageCommands.parseFlightPlay("scarlet fade 20");
        assertNotNull(fade);
        assertEquals(StageClientScene.Transition.FADE, fade.transition());
        assertEquals(20, fade.ticks());

        var blur = DynamicStageCommands.parseFlightPlay("scarlet blur 40");
        assertNotNull(blur);
        assertEquals(StageClientScene.Transition.BLUR, blur.transition());
        assertEquals(40, blur.ticks());
    }

    @Test
    void rejectsIncompleteOrInvalidFlightTransitions() {
        assertNull(DynamicStageCommands.parseFlightPlay(null));
        assertNull(DynamicStageCommands.parseFlightPlay(""));
        assertNull(DynamicStageCommands.parseFlightPlay("scarlet fade"));
        assertNull(DynamicStageCommands.parseFlightPlay("scarlet wipe 20"));
        assertNull(DynamicStageCommands.parseFlightPlay("scarlet blur 1"));
        assertNull(DynamicStageCommands.parseFlightPlay("scarlet fade nope"));
    }
}
