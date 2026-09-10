package com.tacz.guns.client.renderer.item;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.animation.IFPAnimationInstance;
import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.statemachine.AnimationState;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.client.animation.statemachine.LuaStateMachineFactory;
import com.tacz.guns.client.animation.statemachine.GunAnimationConstant;
import com.tacz.guns.client.animation.statemachine.ItemAnimationStateContext;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimateGeoItemRendererTest {
    @Test
    void pendingDrawRetriesWhenTheActualStateMachineBecomesReady() {
        FixtureRenderer renderer = new FixtureRenderer();
        IFPAnimationInstance instance = renderer.instance();
        for (int i = 0; i < 3; i++) instance.triggerDraw();
        int prematureSounds = renderer.drawSounds;

        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        for (int i = 0; i < 20; i++) instance.triggerDraw();

        assertAll(
                () -> assertEquals(0, prematureSounds),
                () -> assertTrue(ready.machine.isInitialized()),
                () -> assertEquals(1, renderer.contextInitializations),
                () -> assertEquals(1, ready.initializations),
                () -> assertEquals(1, ready.entries),
                () -> assertEquals(List.of(GunAnimationConstant.INPUT_DRAW), ready.inputs),
                () -> assertEquals(1, renderer.drawSounds));
    }

    @Test
    void readyDrawInitializesAndDispatchesSoundOnlyOnce() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        IFPAnimationInstance instance = renderer.instance();

        for (int i = 0; i < 20; i++) instance.triggerDraw();

        assertAll(
                () -> assertEquals(1, renderer.contextInitializations),
                () -> assertEquals(1, ready.initializations),
                () -> assertEquals(1, ready.entries),
                () -> assertEquals(0, ready.exits),
                () -> assertEquals(List.of(GunAnimationConstant.INPUT_DRAW), ready.inputs),
                () -> assertEquals(1, renderer.drawSounds));
    }

    @Test
    void invalidationDuringInitializationDoesNotCommitDrawOrSound() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture retired = new MachineFixture();
        renderer.available = retired.machine;
        retired.onInitialize = () -> renderer.available = null;
        IFPAnimationInstance instance = renderer.instance();

        instance.triggerDraw();
        int retiredSounds = renderer.drawSounds;
        instance.triggerDraw();
        MachineFixture replacement = new MachineFixture();
        renderer.available = replacement.machine;
        instance.triggerDraw();

        assertAll(
                () -> assertEquals(0, retiredSounds),
                () -> assertEquals(1, retired.initializations),
                () -> assertFalse(retired.inputs.contains(GunAnimationConstant.INPUT_DRAW)),
                () -> assertFalse(retired.machine.isInitialized()),
                () -> assertTrue(replacement.machine.isInitialized()),
                () -> assertEquals(1, replacement.initializations),
                () -> assertEquals(1, renderer.drawSounds));
    }

    @Test
    void replacementDuringInitializationMustInitializeTheCurrentMachineBeforeCommitting() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture retired = new MachineFixture();
        MachineFixture replacement = new MachineFixture();
        renderer.available = retired.machine;
        retired.onInitialize = () -> renderer.available = replacement.machine;
        IFPAnimationInstance instance = renderer.instance();

        instance.triggerDraw();
        int retiredSounds = renderer.drawSounds;
        instance.triggerDraw();
        instance.triggerDraw();

        assertAll(
                () -> assertEquals(0, retiredSounds),
                () -> assertEquals(1, retired.initializations),
                () -> assertFalse(retired.inputs.contains(GunAnimationConstant.INPUT_DRAW)),
                () -> assertFalse(retired.machine.isInitialized()),
                () -> assertEquals(1, replacement.initializations),
                () -> assertTrue(replacement.machine.isInitialized()),
                () -> assertEquals(1, renderer.drawSounds));
    }

    @Test
    void resetReplacementInstanceRetriesItsOwnPendingRuntime() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture oldRuntime = new MachineFixture();
        renderer.available = oldRuntime.machine;
        IFPAnimationInstance oldInstance = renderer.instance();
        oldInstance.triggerDraw();

        // The real handler reset discards the old instance and later creates a fresh one.
        renderer.available = null;
        IFPAnimationInstance freshInstance = renderer.instance();
        freshInstance.triggerDraw();
        int soundsBeforeReady = renderer.drawSounds;
        MachineFixture newRuntime = new MachineFixture();
        renderer.available = newRuntime.machine;
        freshInstance.triggerDraw();
        freshInstance.triggerDraw();

        assertAll(
                () -> assertNotSame(oldInstance, freshInstance),
                () -> assertEquals(1, soundsBeforeReady),
                () -> assertEquals(1, oldRuntime.initializations),
                () -> assertEquals(1, newRuntime.initializations),
                () -> assertEquals(2, renderer.drawSounds));
    }

    @Test
    void putAwayBeforeFirstDrawPermanentlyCancelsThatInstance() {
        FixtureRenderer renderer = new FixtureRenderer();
        IFPAnimationInstance retiredInstance = renderer.instance();
        retiredInstance.triggerPutAway();
        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        for (int i = 0; i < 20; i++) retiredInstance.triggerDraw();

        assertAll(
                () -> assertFalse(ready.machine.isInitialized()),
                () -> assertEquals(0, ready.initializations),
                () -> assertEquals(0, renderer.drawSounds),
                () -> assertEquals(1, renderer.putAwaySounds));
    }

    @Test
    void putAwayCancelsPendingDrawButANewInstanceCanDraw() {
        FixtureRenderer renderer = new FixtureRenderer();
        IFPAnimationInstance retiredInstance = renderer.instance();
        retiredInstance.triggerDraw();
        retiredInstance.triggerPutAway();
        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        retiredInstance.triggerDraw();
        int soundsBeforeNewInstance = renderer.drawSounds;
        int initsBeforeNewInstance = ready.initializations;

        IFPAnimationInstance newInstance = renderer.instance();
        newInstance.triggerDraw();
        newInstance.triggerDraw();
        assertAll(
                () -> assertEquals(0, soundsBeforeNewInstance),
                () -> assertEquals(0, initsBeforeNewInstance),
                () -> assertEquals(1, ready.initializations),
                () -> assertEquals(1, renderer.drawSounds),
                () -> assertEquals(1, renderer.putAwaySounds));
    }

    @Test
    void putAwayDuringInitializationDoesNotCommitDrawAfterTheCallbackReturns() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        IFPAnimationInstance instance = renderer.instance();
        ready.onInitialize = instance::triggerPutAway;

        instance.triggerDraw();
        instance.triggerDraw();

        assertAll(
                () -> assertEquals(1, ready.initializations),
                () -> assertFalse(ready.inputs.contains(GunAnimationConstant.INPUT_DRAW)),
                () -> assertFalse(ready.machine.isInitialized()),
                () -> assertEquals(0, renderer.drawSounds),
                () -> assertEquals(1, renderer.putAwaySounds));
    }

    @Test
    void drawnInstanceExitsOnceAndReturningUsesAFreshInstance() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        IFPAnimationInstance previous = renderer.instance();
        previous.triggerDraw();
        previous.triggerPutAway();
        previous.triggerDraw();
        assertFalse(ready.machine.isInitialized());
        assertEquals(1, ready.exits);

        IFPAnimationInstance returned = renderer.instance();
        returned.triggerDraw();
        returned.triggerDraw();
        assertAll(
                () -> assertNotSame(previous, returned),
                () -> assertTrue(ready.machine.isInitialized()),
                () -> assertEquals(2, ready.initializations),
                () -> assertEquals(2, renderer.drawSounds),
                () -> assertEquals(1, renderer.putAwaySounds));
    }

    @Test
    void initializedMachineStillConsumesFrameUpdatesAndGameplayInputs() {
        FixtureRenderer renderer = new FixtureRenderer();
        MachineFixture ready = new MachineFixture();
        renderer.available = ready.machine;
        renderer.instance().triggerDraw();

        ready.machine.update();
        ready.machine.trigger(GunAnimationConstant.INPUT_IDLE);
        ready.machine.trigger(GunAnimationConstant.INPUT_RELOAD);
        ready.machine.trigger(GunAnimationConstant.INPUT_BOLT);
        ready.machine.update();

        assertAll(
                () -> assertEquals(2, ready.updates),
                () -> assertEquals(List.of(GunAnimationConstant.INPUT_DRAW, GunAnimationConstant.INPUT_IDLE,
                        GunAnimationConstant.INPUT_RELOAD, GunAnimationConstant.INPUT_BOLT), ready.inputs),
                () -> assertSame(ready.machine, ready.machine.getContext().getStateMachine()),
                () -> assertSame(ready.machine.getContext().getTrackArray(),
                        ready.machine.getAnimationController().getUpdatingTrackArray()),
                () -> assertEquals(1, ready.initializations));
    }

    private static final class FixtureRenderer extends AnimateGeoItemRenderer<BedrockAnimatedModel, ItemAnimationStateContext> {
        LuaAnimationStateMachine<ItemAnimationStateContext> available;
        int contextInitializations;
        int drawSounds;
        int putAwaySounds;

        IFPAnimationInstance instance() {
            // Null tokens stay inside this fixture; no ItemStack, player, or FML bootstrap is fabricated.
            return createAnimationInstance(null, null);
        }

        @Override
        public LuaAnimationStateMachine<ItemAnimationStateContext> getStateMachine(ItemStack stack) {
            return available;
        }

        @Override
        public ItemAnimationStateContext initContext(ItemStack stack, Player player, float partialTick) {
            contextInitializations++;
            return new ItemAnimationStateContext();
        }

        @Override
        public void updateContext(ItemAnimationStateContext context, ItemStack stack, Player player, float partialTick) {
            context.setPartialTicks(partialTick);
        }

        @Override
        Player animationPlayer() {
            return null;
        }

        @Override
        void playDrawSound(ItemStack stack, Player player) {
            drawSounds++;
        }

        @Override
        void playPutAwaySound(ItemStack stack, Player player) {
            putAwaySounds++;
        }
    }

    private static final class MachineFixture {
        final LuaAnimationStateMachine<ItemAnimationStateContext> machine;
        final List<String> inputs = new ArrayList<>();
        int initializations;
        int entries;
        int exits;
        int updates;
        Runnable onInitialize = () -> { };

        MachineFixture() {
            LuaTable script = new LuaTable();
            script.set("initialize", new TwoArgFunction() {
                @Override
                public LuaValue call(LuaValue self, LuaValue context) {
                    initializations++;
                    onInitialize.run();
                    return LuaValue.NIL;
                }
            });
            machine = new LuaStateMachineFactory<ItemAnimationStateContext>()
                    .setController(new AnimationController(List.of(), (node, type) -> null))
                    .setLuaScripts(script).build();
            machine.setStatesSupplier(() -> List.of(new AnimationState<ItemAnimationStateContext>() {
                @Override
                public void update(ItemAnimationStateContext context) {
                    updates++;
                }

                @Override
                public void entryAction(ItemAnimationStateContext context) {
                    entries++;
                }

                @Override
                public void exitAction(ItemAnimationStateContext context) {
                    exits++;
                }

                @Override
                public AnimationState<ItemAnimationStateContext> transition(ItemAnimationStateContext context, String condition) {
                    inputs.add(condition);
                    return null;
                }
            }));
        }
    }
}
