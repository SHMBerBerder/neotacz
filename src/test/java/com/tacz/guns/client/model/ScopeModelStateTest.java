package com.tacz.guns.client.model;

import com.google.gson.Gson;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScopeModelStateTest {
    @BeforeAll
    static void bootstrap() { MinecraftTestEnvironment.bootstrap(); }

    @Test
    void twoGunSnapshotsRestorePartsSelectorsConstraintsAndPendingDelegatesOnNestedFailure() throws Exception {
        BedrockGunModel model = new BedrockGunModel(pojo(), BedrockVersion.NEW);
        configureGun(model, 1);
        ItemStack originalA = (ItemStack) get(model, "currentGunItem");
        var first = model.captureRenderState();
        originalA.setCount(19);
        configureGun(model, 2);
        var second = model.captureRenderState();
        configureGun(model, 3);
        model.delegateRender((pose, buffer, context, light, overlay) -> {});
        Object pending = get(model, "delegateRenderers");
        RuntimeException failure = new RuntimeException("scope draw");
        first.accept(() -> {
            assertGun(model, 1);
            assertSame(failure, assertThrows(RuntimeException.class, () -> second.accept(() -> {
                assertGun(model, 2);
                model.delegateRender((pose, buffer, context, light, overlay) -> fail("must not leak"));
                model.cleanAnimationTransform();
                throw failure;
            })));
            assertGun(model, 1);
        });
        assertGun(model, 3);
        assertSame(pending, get(model, "delegateRenderers"));
        second.accept(() -> assertGun(model, 2));
        assertGun(model, 3);
    }

    @Test
    void attachmentSnapshotsKeepEachGunAndAttachmentWhileTheirSharedModelChanges() throws Exception {
        BedrockAttachmentModel model = new BedrockAttachmentModel(pojo(), BedrockVersion.NEW);
        ItemStack firstGun = new ItemStack(Items.STICK, 4);
        ItemStack firstAttachment = new ItemStack(Items.PAPER, 5);
        model.prepareInstalledRenderState(firstAttachment, firstGun);
        configureParts(model, 1);
        var first = model.captureRenderState();
        firstGun.setCount(17);
        firstAttachment.setCount(18);
        model.prepareInstalledRenderState(new ItemStack(Items.PAPER, 7), new ItemStack(Items.STICK, 6));
        configureParts(model, 2);
        var second = model.captureRenderState();
        first.accept(() -> {
            assertParts(model, 1);
            assertEquals(4, stackCount(model, "currentGunItem"));
            assertEquals(5, stackCount(model, "attachmentItem"));
            second.accept(() -> {
                assertParts(model, 2);
                assertEquals(6, stackCount(model, "currentGunItem"));
                assertEquals(7, stackCount(model, "attachmentItem"));
            });
            assertParts(model, 1);
        });
        assertParts(model, 2);
        assertEquals(6, stackCount(model, "currentGunItem"));
    }

    @Test
    void synchronousAnimationResetPreventsBlendAccumulationWithoutErasingDeferredFrames() {
        BedrockGunModel model = new BedrockGunModel(pojo(), BedrockVersion.NEW);
        var translation = model.supplyListeners("root", ObjectAnimationChannel.ChannelType.TRANSLATION);
        var rotation = model.supplyListeners("root", ObjectAnimationChannel.ChannelType.ROTATION);
        var scale = model.supplyListeners("root", ObjectAnimationChannel.ChannelType.SCALE);
        Runnable animate = () -> {
            translation.update(new float[]{1, 2, 3}, true);
            rotation.update(new float[]{0.1F, 0.2F, 0.3F}, true);
            scale.update(new float[]{2, 3, 4}, true);
        };
        animate.run();
        var first = model.captureRenderState();
        Quaternionf expectedRotation = new Quaternionf(model.getRootNode().additionalQuaternion);
        model.cleanAnimationTransform();
        animate.run();
        var second = model.captureRenderState();
        assertEquals(1F, model.getRootNode().offsetX);
        assertEquals(2F, model.getRootNode().xScale);
        assertEquals(expectedRotation, model.getRootNode().additionalQuaternion);
        model.cleanAnimationTransform();
        Runnable assertFrame = () -> {
            assertEquals(1F, model.getRootNode().offsetX);
            assertEquals(-2F, model.getRootNode().offsetY);
            assertEquals(3F, model.getRootNode().offsetZ);
            assertEquals(2F, model.getRootNode().xScale);
            assertEquals(expectedRotation, model.getRootNode().additionalQuaternion);
        };
        first.accept(() -> {
            assertFrame.run();
            second.accept(assertFrame);
            assertFrame.run();
        });
        assertEquals(0F, model.getRootNode().offsetX);
        assertEquals(1F, model.getRootNode().xScale);
        assertEquals(new Quaternionf(), model.getRootNode().additionalQuaternion);
    }

    @SuppressWarnings("unchecked")
    private static void configureGun(BedrockGunModel model, int seed) throws Exception {
        set(model, "currentGunItem", new ItemStack(Items.STICK, seed));
        Map<AttachmentType, ItemStack> attachments = (Map<AttachmentType, ItemStack>) get(model, "currentAttachmentItem");
        attachments.clear();
        attachments.put(AttachmentType.SCOPE, new ItemStack(Items.PAPER, seed + 1));
        Set<String> adapters = (Set<String>) get(model, "adapterToRender");
        adapters.clear();
        adapters.add("adapter" + seed);
        set(model, "currentExtendMagLevel", seed);
        set(model, "renderMount", seed % 2 == 0);
        model.setRenderHand(seed % 2 != 0);
        configureParts(model, seed);
    }

    private static void assertGun(BedrockGunModel model, int seed) {
        assertParts(model, seed);
        assertEquals(seed, stackCount(model, "currentGunItem"));
        try {
            Map<?, ?> attachments = (Map<?, ?>) get(model, "currentAttachmentItem");
            assertEquals(seed + 1, ((ItemStack) attachments.get(AttachmentType.SCOPE)).getCount());
            assertEquals(Set.of("adapter" + seed), get(model, "adapterToRender"));
            assertEquals(seed, get(model, "currentExtendMagLevel"));
            assertEquals(seed % 2 == 0, get(model, "renderMount"));
            assertEquals(seed % 2 != 0, model.getRenderHand());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void configureParts(BedrockAnimatedModel model, int seed) {
        BedrockPart part = model.getRootNode();
        part.setPos(seed, seed + 1, seed + 2);
        part.xRot = seed + 3;
        part.yRot = seed + 4;
        part.zRot = seed + 5;
        part.xScale = seed + 6;
        part.yScale = seed + 7;
        part.zScale = seed + 8;
        part.offsetX = seed + 9;
        part.offsetY = seed + 10;
        part.offsetZ = seed + 11;
        part.additionalQuaternion.rotationXYZ(seed / 10F, seed / 20F, seed / 30F);
        part.visible = seed % 2 == 0;
        part.illuminated = seed % 2 != 0;
        part.children.getFirst().offsetX = seed + 12;
        model.getConstraintObject().translationConstraint.set(seed, seed + 1, seed + 2);
        model.getConstraintObject().rotationConstraint.set(seed + 3, seed + 4, seed + 5);
    }

    private static void assertParts(BedrockAnimatedModel model, int seed) {
        BedrockPart p = model.getRootNode();
        assertArrayEquals(new float[]{seed, seed + 1, seed + 2, seed + 3, seed + 4, seed + 5,
                        seed + 6, seed + 7, seed + 8, seed + 9, seed + 10, seed + 11},
                new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale,
                        p.offsetX, p.offsetY, p.offsetZ});
        assertEquals(new Quaternionf().rotationXYZ(seed / 10F, seed / 20F, seed / 30F), p.additionalQuaternion);
        assertEquals(seed % 2 == 0, p.visible);
        assertEquals(seed % 2 != 0, p.illuminated);
        assertEquals(seed + 12F, p.children.getFirst().offsetX);
        assertEquals(new Vector3f(seed, seed + 1, seed + 2), model.getConstraintObject().translationConstraint);
        assertEquals(new Vector3f(seed + 3, seed + 4, seed + 5), model.getConstraintObject().rotationConstraint);
    }

    private static int stackCount(Object model, String name) {
        try {
            return ((ItemStack) get(model, name)).getCount();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object get(Object object, String name) throws ReflectiveOperationException {
        return field(object.getClass(), name).get(object);
    }

    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        field(object.getClass(), name).set(object, value);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static BedrockModelPOJO pojo() {
        return new Gson().fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"constraint","parent":"root","pivot":[0,24,0]},
                    {"name":"text_node","parent":"root","pivot":[0,24,0]}]}]}
                """, BedrockModelPOJO.class);
    }
}
