package com.tacz.guns.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import com.tacz.guns.util.RenderHelper;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import com.tacz.guns.client.renderer.item.FirstPersonHandSway;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import static org.junit.jupiter.api.Assertions.*;

class ScopePreparedRenderTest {
    @BeforeAll
    static void bootstrap() { MinecraftTestEnvironment.bootstrap(); }

    @Test
    void rejectedAndNonFirstPersonPlansKeepExactlyOneUncapturedFunctionalPrepass() {
        for (ItemDisplayContext context : new ItemDisplayContext[]{ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND}) {
            BedrockGunModel model = new BedrockGunModel(ScopeModelStateTest.pojo(), BedrockVersion.NEW);
            int[] effects = {0};
            int[] submissions = {0};
            SubmitNodeCollector collector = collector(submissions);
            model.setFunctionalRenderer("text_node", part -> effect(() -> {
                effects[0]++;
                RenderHelper.submitCustomGeometry(collector, new PoseStack(), null, (p, b) -> {});
            }));
            var plan = model.prepareRender(collector, new PoseStack(), null, new ItemStack(ModItems.MODERN_KINETIC_GUN.get()),
                    context, null, null, 17, 23, -860_000, null, false, false);
            assertThrows(IllegalStateException.class, plan::submitScope);
            ScopeRenderExtras unrelated = new ScopeRenderExtras(collector);
            RenderHelper.withSubmitNodeCollector(collector, () -> unrelated.capture(() ->
                    plan.collectDeferredFunctionalRenderers(new PoseStack())));
            assertFalse(plan.submitScope());
            assertEquals(1, effects[0]);
            assertEquals(1, submissions[0]);
            unrelated.emit(null, type -> { fail("Nested rejected gun must not enter another gun's scope"); return null; });
            assertThrows(IllegalStateException.class, () -> plan.collectDeferredFunctionalRenderers(new PoseStack()));
            assertThrows(IllegalStateException.class, plan::submitScope);
            plan.renderGunBodyToBuffer(new PoseStack(), RenderHelper.noopVertexConsumer());
            assertEquals(1, effects[0]);
        }
    }

    @Test
    void prepassExceptionsKeepCallerPoseAndStateAndCannotSubmitPartialCapturedWork() {
        BedrockGunModel model = new BedrockGunModel(ScopeModelStateTest.pojo(), BedrockVersion.NEW);
        RuntimeException failure = new RuntimeException("functional prepass");
        model.setFunctionalRenderer("text_node", part -> effect(() -> {
            model.getRootNode().offsetX = 99;
            model.delegateRender((p, b, c, l, o) -> fail("failed prepass delegate must not leak"));
            throw failure;
        }));
        PoseStack pose = new PoseStack();
        pose.translate(1, 2, 3);
        var before = pose.last();
        model.getRootNode().offsetX = 7;
        var plan = model.prepareRender(collector(new int[1]), pose, null, new ItemStack(ModItems.MODERN_KINETIC_GUN.get()),
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, null, 17, 23, 0, null, false, false);
        assertSame(failure, assertThrows(RuntimeException.class, () -> plan.collectDeferredFunctionalRenderers(pose)));
        assertSame(before, pose.last());
        assertEquals(7F, model.getRootNode().offsetX);
        assertNull(RenderHelper.currentSubmitNodeCollector());
        assertFalse(RenderHelper.isCollectingDeferredFunctionalRenderers());
        assertThrows(IllegalStateException.class, plan::submitScope);
        assertThrows(IllegalStateException.class, () -> plan.collectDeferredFunctionalRenderers(pose));
        model.setFunctionalRenderer("text_node", part -> effect(() -> {}));
        var next = model.prepareRender(collector(new int[1]), pose, null, new ItemStack(ModItems.MODERN_KINETIC_GUN.get()),
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, null, 17, 23, 0, null, false, false);
        assertDoesNotThrow(() -> next.collectDeferredFunctionalRenderers(pose));
    }

    @Test
    void acceptedResolvedPlanCapturesOnlyItsDelegateIntervalAndSubmitsExactlyOnce() throws Exception {
        BedrockGunModel model = new BedrockGunModel(ScopeModelStateTest.pojo(), BedrockVersion.NEW);
        SubmitNodeStorage storage = new SubmitNodeStorage();
        SubmitNodeCollection collection = storage.order(-960_000);
        ScopeRenderExtras extras = new ScopeRenderExtras(storage);
        int[] effects = {0};
        int[] geometry = {0};
        model.setFunctionalRenderer("text_node", part -> effect(() -> {
            effects[0]++;
            RenderHelper.submitCustomGeometry(storage, new PoseStack(), null, (p, b) -> geometry[0]++);
        }));
        var submit = new ScopeStencilFeatureRenderer.ScopeSubmit(model,
                new BedrockAttachmentModel(ScopeModelStateTest.pojo(), BedrockVersion.NEW), null,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                null, null, null, null, new Matrix4f(), new Matrix3f(), null,
                false, true, -1, 17, 23, false, extras);
        Class<?> passType = Class.forName(BedrockGunModel.class.getName() + "$ScopePass");
        var passConstructor = passType.getDeclaredConstructor(SubmitNodeCollection.class,
                ScopeStencilFeatureRenderer.ScopeSubmit.class);
        passConstructor.setAccessible(true);
        Object pass = passConstructor.newInstance(collection, submit);
        var planConstructor = BedrockGunModel.PreparedRender.class.getDeclaredConstructor(BedrockGunModel.class,
                boolean.class, passType, SubmitNodeCollector.class, ItemDisplayContext.class, int.class, int.class,
                Consumer.class, FirstPersonHandSway.class, boolean.class);
        planConstructor.setAccessible(true);
        var plan = (BedrockGunModel.PreparedRender) planConstructor.newInstance(model, true, pass, storage,
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, 17, 23, model.captureRenderState(), null, false);
        plan.collectDeferredFunctionalRenderers(new PoseStack());
        assertEquals(1, effects[0]);
        assertEquals(0, geometry[0]);
        assertTrue(collection.solid.isEmpty());
        assertTrue(plan.submitScope());
        assertFalse(collection.solid.isEmpty());
        extras.emit(null, type -> RenderHelper.noopVertexConsumer());
        assertEquals(1, geometry[0]);
        assertThrows(IllegalStateException.class, plan::submitScope);
        assertThrows(IllegalStateException.class, () -> plan.collectDeferredFunctionalRenderers(new PoseStack()));
    }

    private static IFunctionalRenderer effect(Runnable action) {
        return new IFunctionalRenderer() {
            @Override
            public boolean usesRetainedSubmitPrepass() { return true; }

            @Override
            public void render(PoseStack pose, VertexConsumer buffer, ItemDisplayContext context, int light, int overlay) {
                action.run();
            }
        };
    }

    private static SubmitNodeCollector collector(int[] submissions) {
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry")) {
                        submissions[0]++;
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
    }
}
