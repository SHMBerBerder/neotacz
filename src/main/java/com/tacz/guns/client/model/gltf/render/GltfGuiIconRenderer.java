package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Objects;

/** Static mesh submission into Minecraft's existing GUI item atlas, without another texture cache. */
public final class GltfGuiIconRenderer {
    private static final Snapshot MISSING = new Snapshot(new Object(), null, MissingTextureAtlasSprite.getLocation());
    private static final float ICON_SPAN = 0.9F;

    private GltfGuiIconRenderer() { }

    /** Capture once during item extraction; only cacheIdentity belongs in the atlas key. */
    public static Snapshot capture(ItemStack stack) {
        return TimelessAPI.getGunDisplay(stack)
                .map(display -> capture(display.guiModelSnapshot())).orElse(MISSING);
    }

    static Snapshot capture(GunDisplayInstance.GuiModelSnapshot source) {
        Identifier fallback = source.meshRequested() ? MissingTextureAtlasSprite.getLocation() : source.slotTexture();
        if (source.meshRequested() && source.model() != null
                && source.model().getBodyRenderer() instanceof GltfGunBodyRenderer renderer
                && renderer.isGuiIconAvailable()) {
            return new Snapshot(renderer.guiIconIdentity(), renderer, fallback);
        }
        return new Snapshot(source.pendingIdentity(), null, fallback);
    }

    @Nullable
    public static Snapshot captureAttachment(ItemStack stack) {
        if (!(stack.getItem() instanceof IAttachment attachment)) return null;
        return TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(stack))
                .filter(ClientAttachmentIndex::usesMeshRenderModel)
                .map(index -> captureAttachment(index.guiMeshSnapshot())).orElse(null);
    }

    public static Snapshot captureAttachment(ClientAttachmentIndex.GuiMeshSnapshot source) {
        GltfGunBodyRenderer renderer = source.renderer();
        Identifier fallback = MissingTextureAtlasSprite.getLocation();
        if (renderer != null && renderer.isGuiIconAvailable()) {
            return new Snapshot(renderer.guiIconIdentity(), renderer, fallback);
        }
        return new Snapshot(source.pendingIdentity(), null, fallback);
    }

    public static boolean submit(Snapshot snapshot, PoseStack poseStack,
                                 @Nullable OrderedSubmitNodeCollector collector, int light, int overlay) {
        return snapshot.renderer() != null && snapshot.renderer().submitGuiIcon(poseStack, collector, light, overlay);
    }

    @Nullable
    static Frame prepare(GltfGunBodyRenderer.PreparedFrame prepared) {
        if (prepared.primitives().isEmpty()) return null;
        // Use one asset-independent three-quarter view, then fit the actual projected geometry.
        Matrix4f view = new Matrix4f().rotationXYZ((float) Math.toRadians(20), (float) Math.toRadians(-45), 0);
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f projected = new Vector3f();
        prepared.primitives().forEach(primitive -> primitive.geometry().visitPositions(point -> {
            projected.set(point).mulPosition(view);
            minimum.min(projected);
            maximum.max(projected);
        }));
        if (!minimum.isFinite() || !maximum.isFinite()) return null;
        float span = Math.max(maximum.x - minimum.x, Math.max(maximum.y - minimum.y, maximum.z - minimum.z));
        if (!Float.isFinite(span) || span <= 0) return null;
        float fitScale = ICON_SPAN / span;
        if (!Float.isFinite(fitScale) || fitScale <= 0) return null;
        Vector3f center = new Vector3f(minimum).mul(0.5F).fma(0.5F, maximum);
        // Vanilla ItemTransform (including NO_TRANSFORM) subtracts 0.5 from item coordinates.
        Matrix4f fit = new Matrix4f().translation(0.5F, 0.5F, 0.5F)
                .scale(fitScale).translate(-center.x, -center.y, -center.z).mul(view);
        return fit.isFinite() ? new Frame(prepared, fit) : null;
    }

    public record Snapshot(Object cacheIdentity, @Nullable GltfGunBodyRenderer renderer, Identifier fallbackTexture) {
        public Snapshot {
            Objects.requireNonNull(cacheIdentity, "cacheIdentity");
            Objects.requireNonNull(fallbackTexture, "fallbackTexture");
        }
    }

    record Frame(GltfGunBodyRenderer.PreparedFrame prepared, Matrix4f transform) {
        Frame {
            transform = new Matrix4f(transform);
        }

        @Override
        public Matrix4f transform() {
            return new Matrix4f(transform);
        }
    }
}
