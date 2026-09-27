package com.tacz.guns.client.model;

import com.google.gson.Gson;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import com.tacz.guns.api.item.nbt.ItemStackNbtHelper;
import com.tacz.guns.client.model.gltf.render.GunBodyRenderer;
import com.tacz.guns.client.renderer.item.FirstPersonHandSway;
import com.tacz.guns.client.resource.ClientIndexManager;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.manager.CommonDataManager;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ScopeCustomBodyRoutingTest {
    private static final Identifier GUN = Identifier.fromNamespaceAndPath("test", "scope_body_gun");
    private static final Identifier SCOPE = Identifier.fromNamespaceAndPath("test", "scope_body_attachment");

    @BeforeAll
    static void bootstrap() { MinecraftTestEnvironment.bootstrap(); }

    @Test
    void realPrepareEntryAcceptsBuiltinAndInstalledScopesWithoutDuplicateLocalOptics() throws Exception {
        for (boolean builtin : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(builtin)) {
                Body body = new Body();
                fixture.rig.setBodyRenderer(body);
                var swayConstructor = FirstPersonHandSway.class.getDeclaredConstructor(float.class, float.class,
                        float.class, float.class);
                swayConstructor.setAccessible(true);
                FirstPersonHandSway sway = swayConstructor.newInstance(0, 0, 3, 4);
                var plan = fixture.plan(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, sway);
                assertEquals(1, body.prepares);
                assertNotEquals(0, body.preparedOffset);
                assertEquals(0, fixture.rig.getRootNode().offsetX);
                plan.collectDeferredFunctionalRenderers(new PoseStack());
                assertEquals(1, fixture.effects);
                assertEquals(0, fixture.semantics);
                assertTrue(fixture.storage.order(-960_000).solid.isEmpty());
                assertTrue(plan.submitScope());
                assertEquals(1, fixture.semantics);
                var submits = fixture.scopeSubmits();
                assertEquals(1, submits.size());
                assertSame(body.snapshot, submits.getFirst().customBody());
                assertTrue(submits.getFirst().renderHand());
                assertEquals(0, body.ordinarySubmits);
                assertTrue(fixture.storage.order(-860_000).solid.isEmpty());
                assertThrows(IllegalStateException.class, plan::submitScope);
                assertThrows(IllegalStateException.class, () -> plan.collectDeferredFunctionalRenderers(new PoseStack()));
            }
        }
    }

    @Test
    void unsupportedBackendKeepsLocalOpticsAndOrdinaryCustomBody() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            Body body = new Body();
            body.supported = false;
            fixture.rig.setBodyRenderer(body);
            var plan = fixture.plan(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null);
            plan.collectDeferredFunctionalRenderers(new PoseStack());
            assertFalse(plan.submitScope());
            assertEquals(1, fixture.effects);
            assertEquals(1, fixture.semantics);
            var local = fixture.scopeSubmits();
            assertEquals(1, local.size());
            assertNull(local.getFirst().gunModel());
            assertTrue(fixture.rig.submitCustomBody(fixture.storage.order(-860_000), new PoseStack(), fixture.gun,
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, 17, 23));
            assertEquals(1, body.ordinarySubmits);
            assertSame(body, fixture.rig.getBodyRenderer());
        }
    }

    @Test
    void preparationFailureDisablesBackendWithoutSubmittingPartialOrBedrockScope() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            Body body = new Body();
            body.failure = new IllegalStateException("controlled material preparation failure");
            fixture.rig.setBodyRenderer(body);
            var plan = assertDoesNotThrow(() -> fixture.plan(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null));
            assertNull(fixture.rig.getBodyRenderer());
            plan.collectDeferredFunctionalRenderers(new PoseStack());
            assertFalse(plan.submitScope());
            assertTrue(fixture.scopeSubmits().isEmpty());
            assertEquals(0, body.ordinarySubmits);
            assertEquals(0, fixture.rig.getRootNode().offsetX);
        }
    }

    @Test
    void nonFirstPersonAndUnavailableBackendsDoNotPrepareScopedBody() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            Body body = new Body();
            fixture.rig.setBodyRenderer(body);
            for (ItemDisplayContext context : ItemDisplayContext.values()) {
                if (!context.firstPerson()) fixture.plan(context, null);
            }
            assertEquals(0, body.prepares);
            body.available = false;
            fixture.plan(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null);
            assertEquals(0, body.prepares);
        }
    }

    @Test
    void retirementAffectsOnlyItsCapturedSubmitAndValidEmptyBodyRemainsHandled() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            Body first = new Body();
            fixture.rig.setBodyRenderer(first);
            var plan = fixture.plan(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null);
            plan.collectDeferredFunctionalRenderers(new PoseStack());
            assertTrue(plan.submitScope());
            var firstSubmit = fixture.scopeSubmits().getFirst();
            Body second = new Body();
            fixture.rig.setBodyRenderer(second);
            plan = fixture.plan(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null);
            plan.collectDeferredFunctionalRenderers(new PoseStack());
            assertTrue(plan.submitScope());
            var secondSubmit = fixture.scopeSubmits().getFirst();
            first.available = false;
            assertFalse(firstSubmit.isAvailable());
            assertTrue(secondSubmit.isAvailable());
            secondSubmit.customBody().emit(type -> { fail("Empty body must not request a buffer"); return null; });
            assertEquals(1, second.emissions);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stagedRetirementSkipsOnlyItsDrawsAndAlwaysExecutesStencilClears() throws Exception {
        Body first = new Body(), second = new Body();
        List<StagedVertexBuffer.Draw> executed = new ArrayList<>();
        try (StagedVertexBuffer staging = new StagedVertexBuffer(() -> "Scope owner test", 256) {
            @Override public ExecuteInfo getExecuteInfo(Draw draw) { executed.add(draw); return null; }
        }) {
            Class<?> groupType = Class.forName(ScopeStencilFeatureRenderer.class.getName() + "$Group");
            Class<?> stageType = Class.forName(ScopeStencilFeatureRenderer.class.getName() + "$DrawStage");
            Class<?> controlType = Class.forName(ScopeStencilFeatureRenderer.class.getName() + "$Control");
            var groupConstructor = groupType.getDeclaredConstructors()[0];
            groupConstructor.setAccessible(true);
            Object group = groupConstructor.newInstance(staging, null);
            var stageConstructor = stageType.getDeclaredConstructors()[0];
            stageConstructor.setAccessible(true);
            var stagesField = groupType.getDeclaredField("stages");
            stagesField.setAccessible(true);
            List<Object> stages = (List<Object>) stagesField.get(group);
            var control = groupType.getDeclaredMethod("control", controlType);
            control.setAccessible(true);
            Object clear = Enum.valueOf((Class) controlType, "CLEAR_STENCIL");
            Object draw = Enum.valueOf((Class) controlType, "DRAW");
            var a = staging.appendDraw(DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES);
            var b = staging.appendDraw(DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES);
            set(group, "currentBody", first.snapshot);
            control.invoke(group, clear);
            stages.add(stageConstructor.newInstance(null, a, draw, first.snapshot));
            stages.add(stageConstructor.newInstance(null, b, draw, second.snapshot));
            control.invoke(group, clear);
            stages.add(stageConstructor.newInstance(null, a, draw, first.snapshot));
            control.invoke(group, clear);
            stages.add(stageConstructor.newInstance(null, b, draw, second.snapshot));
            control.invoke(group, clear);
            first.available = false;

            ScopeStencilFeatureRenderer renderer = new ScopeStencilFeatureRenderer();
            var groups = ScopeStencilFeatureRenderer.class.getDeclaredField("groups");
            groups.setAccessible(true);
            ((List<Object>) groups.get(renderer)).add(group);
            FeatureFrameContext context = new FeatureFrameContext(null, null, null, null, null, null, null, staging);
            CompiledRenderPipeline compiled = new CompiledRenderPipeline() {
                @Override public boolean isClosed() { return false; }
                @Override public void close() { }
            };
            PipelineCache cache = new PipelineCache(null, null) {
                @Override public CompiledRenderPipeline get(RenderPipeline pipeline) {
                    assertSame(ScopeStencilRenderTypes.clearStencilPipeline(), pipeline);
                    return compiled;
                }
            };
            List<String> clears = new ArrayList<>();
            RenderPass pass = (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(),
                    new Class<?>[]{RenderPass.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setPipeline" -> { assertSame(compiled, args[0]); clears.add("pipeline"); }
                            case "draw" -> { assertArrayEquals(new Object[]{3, 1, 0, 0}, args); clears.add("draw"); }
                            case "disableScissor", "enableScissor" -> { }
                            default -> fail("Unexpected render-pass call: " + method.getName());
                        }
                        return null;
                    });
            // NeoForge pipeline modifiers enforce render-thread ownership before consulting the cache.
            var renderThread = RenderSystem.class.getDeclaredField("renderThread");
            renderThread.setAccessible(true);
            Object previousThread = renderThread.get(null);
            try {
                renderThread.set(null, Thread.currentThread());
                PipelineCache previous = RenderSystem.setCurrentPipelineCache(cache);
                try {
                    renderer.executeGroup(context, null, pass, 0, List.of(), true);
                    assertEquals(List.of(b, b), executed);
                    assertEquals(List.of("pipeline", "draw", "pipeline", "draw", "pipeline", "draw", "pipeline", "draw"), clears);
                } finally {
                    RenderSystem.setCurrentPipelineCache(previous);
                    renderer.finishExecute(context);
                }
            } finally {
                renderThread.set(null, previousThread);
            }
        }
    }

    @Test
    void everyStagedDrawCapturesItsSubmitOwnerRatherThanMutableGroupState() throws Exception {
        // Binding bytecode complements the actual execute test; it is not native prepareGroup/GPU proof.
        String renderer = ScopeStencilFeatureRenderer.class.getName().replace('.', '/');
        ClassNode group = bytecode(renderer + "$Group");
        int builders = 0;
        for (var method : group.methods) {
            if (!method.name.startsWith("vertexBuilder")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(renderer + "$DrawStage")
                        && call.name.equals("<init>")) {
                    var owner = assertInstanceOf(FieldInsnNode.class, call.getPrevious());
                    assertEquals(Opcodes.GETFIELD, owner.getOpcode());
                    assertEquals(renderer + "$Group", owner.owner);
                    assertEquals("currentBody", owner.name);
                    builders++;
                }
            }
        }
        assertEquals(3, builders);
        var prepare = bytecode(renderer).methods.stream().filter(method -> method.name.equals("prepareGroup")
                && Arrays.stream(method.instructions.toArray()).anyMatch(instruction -> instruction instanceof FieldInsnNode field
                && field.name.equals("currentBody"))).findFirst().orElseThrow();
        assertTrue(Arrays.stream(prepare.instructions.toArray()).anyMatch(instruction -> instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("currentBody")
                && field.getPrevious() instanceof FieldInsnNode source && source.getOpcode() == Opcodes.GETFIELD
                && source.owner.equals(renderer + "$ScopeSubmit") && source.name.equals("customBody")));
    }

    private static ClassNode bytecode(String name) throws Exception {
        try (var input = ScopeCustomBodyRoutingTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input);
            ClassNode result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }

    private static final class Body implements GunBodyRenderer {
        boolean available = true;
        boolean supported = true;
        RuntimeException failure;
        int prepares, ordinarySubmits, emissions;
        float preparedOffset;
        final PreparedScopeBody snapshot = new PreparedScopeBody() {
            @Override public boolean isAvailable() { return available; }
            @Override public void emit(Function<RenderType, VertexConsumer> buffers) { emissions++; }
        };

        @Override public boolean isAvailable() { return available; }
        @Override public PreparedScopeBody prepareScopeBody(BedrockGunModel rig, PoseStack pose, ItemStack gun,
                ItemDisplayContext context, int light, int overlay) {
            prepares++;
            preparedOffset = rig.getRootNode().offsetX;
            assertEquals(17, light);
            assertEquals(23, overlay);
            if (failure != null) throw failure;
            return supported ? snapshot : null;
        }
        @Override public boolean submit(BedrockGunModel rig, PoseStack pose, ItemStack gun,
                ItemDisplayContext context, OrderedSubmitNodeCollector collector, int light, int overlay) {
            ordinarySubmits++;
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<Identifier, ClientAttachmentIndex> previousAttachments = ClientIndexManager.ATTACHMENT_INDEX;
        final Map<Identifier, CommonGunIndex> common;
        final CommonGunIndex previousGun;
        final BedrockGunModel rig = new BedrockGunModel(pojo(), BedrockVersion.NEW);
        final SubmitNodeStorage storage = new SubmitNodeStorage();
        final ItemStack gun = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
        int semantics, effects;

        @SuppressWarnings("unchecked")
        Fixture(boolean builtin) throws Exception {
            var managerField = CommonAssetsManager.class.getDeclaredField("gunIndex");
            managerField.setAccessible(true);
            common = ((CommonDataManager<CommonGunIndex>) managerField.get(CommonAssetsManager.get())).getAllData();
            previousGun = common.get(GUN);
            CommonGunIndex commonIndex = construct(CommonGunIndex.class);
            GunData data = new GunData();
            if (builtin) data.getBuiltInAttachments().put(AttachmentType.SCOPE, SCOPE);
            set(data, "allowAttachments", List.of(AttachmentType.SCOPE));
            set(commonIndex, "gunData", data);
            common.put(GUN, commonIndex);
            IGun.getIGunOrNull(gun).setGunId(gun, GUN);
            if (!builtin) {
                ItemStack attachment = AttachmentItemBuilder.create().setId(SCOPE).build();
                ItemStackNbtHelper.updateTag(gun, tag -> tag.put(GunItemDataAccessor.GUN_ATTACHMENT_BASE + "SCOPE",
                        ItemStackNbtHelper.saveLegacyStackSubset(attachment)));
            }
            ClientAttachmentIndex index = construct(ClientAttachmentIndex.class);
            AttachmentDisplay display = new AttachmentDisplay();
            display.init();
            BedrockAttachmentModel model = new BedrockAttachmentModel(pojo(), BedrockVersion.NEW) {
                @Override public void submitInstalledSemantics(ItemStack attachment, ItemStack item, PoseStack pose,
                        ItemDisplayContext context, int light, int overlay) { semantics++; }
            };
            model.setIsScope(true);
            set(index, "display", display);
            set(index, "attachmentModel", model);
            set(index, "modelTexture", Identifier.fromNamespaceAndPath("test", "scope.png"));
            set(index, "modelsLoaded", true);
            set(index, "isScope", true);
            set(index, "views", new int[]{1});
            ClientIndexManager.ATTACHMENT_INDEX = new HashMap<>(Map.of(SCOPE, index));
            rig.setFunctionalRenderer("effect", part -> new IFunctionalRenderer() {
                @Override public boolean usesRetainedSubmitPrepass() { return true; }
                @Override public void render(PoseStack pose, VertexConsumer buffer, ItemDisplayContext context,
                        int light, int overlay) { effects++; }
            });
        }

        BedrockGunModel.PreparedRender plan(ItemDisplayContext context, FirstPersonHandSway sway) {
            return rig.prepareRender(storage, new PoseStack(), null, gun, context, null,
                    Identifier.fromNamespaceAndPath("test", "gun.png"), 17, 23, -860_000, sway, true, false);
        }

        List<ScopeStencilFeatureRenderer.ScopeSubmit> scopeSubmits() {
            List<ScopeStencilFeatureRenderer.ScopeSubmit> submits = new ArrayList<>();
            storage.order(-960_000).solid.sortInto((submit, ordered) -> {
                assertInstanceOf(ScopeStencilFeatureRenderer.ScopeSubmit.class, submit);
                submits.add((ScopeStencilFeatureRenderer.ScopeSubmit) submit);
            });
            return submits;
        }

        @Override public void close() {
            ClientIndexManager.ATTACHMENT_INDEX = previousAttachments;
            if (previousGun == null) common.remove(GUN); else common.put(GUN, previousGun);
        }
    }

    private static <T> T construct(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static BedrockModelPOJO pojo() {
        return new Gson().fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"scope_pos","parent":"root","pivot":[0,24,0]},
                    {"name":"effect","parent":"root","pivot":[0,24,0]},
                    {"name":"scope_body","parent":"root","pivot":[0,24,0]},
                    {"name":"scope_view","parent":"root","pivot":[0,24,0]},
                    {"name":"ocular","parent":"root","pivot":[0,24,0]},
                    {"name":"division","parent":"root","pivot":[0,24,0]}]}]}
                """, BedrockModelPOJO.class);
    }
}
