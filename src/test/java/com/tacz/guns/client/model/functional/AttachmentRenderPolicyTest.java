package com.tacz.guns.client.model.functional;

import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AttachmentRenderPolicyTest {
    @Test
    void onlyMountedFirstPersonOpticsRequireTheirSemanticRigBeforeShowingTheMesh() {
        for (ItemDisplayContext context : ItemDisplayContext.values()) {
            assertEquals(context.firstPerson(), AttachmentRender.requiresMeshOptics(context, true), context.toString());
            assertFalse(AttachmentRender.requiresMeshOptics(context, false), context.toString());
        }
    }
}
