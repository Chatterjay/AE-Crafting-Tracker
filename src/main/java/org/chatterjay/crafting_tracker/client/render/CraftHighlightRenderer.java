package org.chatterjay.crafting_tracker.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.List;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

import net.minecraft.core.Registry;

import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.api.CraftStatus;
import org.chatterjay.crafting_tracker.client.ClientHighlightCache;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData.HighlightEntry;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = Crafting_tracker.MODID, value = Dist.CLIENT)
public class CraftHighlightRenderer {

    private static final int TYPE_CHEMICAL = 3;
    private static final int MAX_OUTPUTS = 3;

    @SuppressWarnings("deprecation")
    private static final RenderType OVERLAY_NO_DEPTH = RenderType.create(
            "ct_overlay_no_depth",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            () -> net.minecraft.client.renderer.GameRenderer.getPositionColorShader()))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", 519))
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                            "src_to_one",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.blendFunc(
                                        GlStateManager.SourceFactor.SRC_ALPHA,
                                        GlStateManager.DestFactor.ONE);
                            },
                            () -> {
                                RenderSystem.disableBlend();
                                RenderSystem.defaultBlendFunc();
                            }))
                    .createCompositeState(true));

    @SuppressWarnings("deprecation")
    private static final RenderType SPRITE_NO_DEPTH = RenderType.create(
            "ct_sprite_no_depth",
            DefaultVertexFormat.POSITION_TEX,
            VertexFormat.Mode.QUADS,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            () -> net.minecraft.client.renderer.GameRenderer.getPositionTexShader()))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", 519))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .createCompositeState(true));

    @SuppressWarnings("deprecation")
    private static final RenderType TINTED_SPRITE_NO_DEPTH = RenderType.create(
            "ct_tinted_sprite_no_depth",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            () -> net.minecraft.client.renderer.GameRenderer.getPositionTexColorShader()))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", 519))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .createCompositeState(true));

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var highlights = ClientHighlightCache.INSTANCE.getActiveHighlights();
        if (highlights.isEmpty()) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        PoseStack.Pose poseEntry = poseStack.last();
        Matrix4f poseMatrix = poseEntry.pose();

        // Fill pass: outer glow + core fill in one batch (all vertices before endBatch)
        VertexConsumer fillConsumer = bufferSource.getBuffer(OVERLAY_NO_DEPTH);
        for (HighlightEntry entry : highlights) {
            CraftStatus status = CraftStatus.values()[entry.statusOrdinal()];
            int r = (status.color >> 16) & 0xFF;
            int g = (status.color >> 8) & 0xFF;
            int b = status.color & 0xFF;
            renderBoxFill(fillConsumer, poseMatrix, entry.pos(), r, g, b, 30, 0.05f);
            renderBoxFill(fillConsumer, poseMatrix, entry.pos(), r, g, b, 80, 0.005f);
        }
        bufferSource.endBatch(OVERLAY_NO_DEPTH);

        // Multi-output sprite pass: billboard sprite(s) for each entry, side by side
        // Up to 3 sprites, scaled to fit within one block width
        for (HighlightEntry entry : highlights) {
            List<HighlightEntry.OutputItem> outputs = entry.outputs();
            if (outputs == null || outputs.isEmpty()) continue;
            int count = Math.min(outputs.size(), MAX_OUTPUTS);
            float spriteSize = 0.28f;
            float spacing = (count <= 1) ? 0f : 0.25f;
            float startX = -(count - 1) * spacing / 2f;

            for (int i = 0; i < count; i++) {
                HighlightEntry.OutputItem out = outputs.get(i);
                int outputType = out.outputType();

                if (outputType == 0) {
                    renderEntryItem(entry, out, poseStack, camera, bufferSource, startX + i * spacing, spriteSize);
                } else if (outputType == 1) {
                    renderEntryFluid(entry, out, poseStack, camera, bufferSource, startX + i * spacing, spriteSize);
                } else if (outputType == TYPE_CHEMICAL) {
                    renderEntryChemical(entry, out, poseStack, camera, bufferSource, mc, startX + i * spacing, spriteSize);
                }
            }
        }
        bufferSource.endBatch(SPRITE_NO_DEPTH);
        bufferSource.endBatch(TINTED_SPRITE_NO_DEPTH);

        // Pass 3: thick lines — wider for visibility
        RenderSystem.lineWidth(3f);
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        for (HighlightEntry entry : highlights) {
            CraftStatus status = CraftStatus.values()[entry.statusOrdinal()];
            int r = (status.color >> 16) & 0xFF;
            int g = (status.color >> 8) & 0xFF;
            int b = status.color & 0xFF;
            renderBoxOutline(lineConsumer, poseMatrix, poseEntry, entry.pos(), r, g, b, 255);
        }
        bufferSource.endBatch(RenderType.lines());
        RenderSystem.lineWidth(1f);

        poseStack.popPose();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static void renderBoxOutline(VertexConsumer consumer, Matrix4f pose,
                                          PoseStack.Pose poseEntry, BlockPos pos,
                                          int r, int g, int b, int a) {
        float x1 = pos.getX() + 0.001f, y1 = pos.getY() + 0.001f, z1 = pos.getZ() + 0.001f;
        float x2 = x1 + 0.998f, y2 = y1 + 0.998f, z2 = z1 + 0.998f;
        float cr = r / 255f, cg = g / 255f, cb = b / 255f, ca = a / 255f;

        line(consumer, pose, poseEntry, x1, y1, z1, x2, y1, z1, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x2, y1, z1, x2, y1, z2, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x2, y1, z2, x1, y1, z2, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x1, y1, z2, x1, y1, z1, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x1, y2, z1, x2, y2, z1, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x2, y2, z1, x2, y2, z2, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x2, y2, z2, x1, y2, z2, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x1, y2, z2, x1, y2, z1, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x1, y1, z1, x1, y2, z1, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x2, y1, z1, x2, y2, z1, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x2, y1, z2, x2, y2, z2, cr, cg, cb, ca);
        line(consumer, pose, poseEntry, x1, y1, z2, x1, y2, z2, cr, cg, cb, ca);
    }

    private static void renderBoxFill(VertexConsumer consumer, Matrix4f pose,
                                       BlockPos pos, int r, int g, int b, int a,
                                       float expand) {
        float x1 = pos.getX() - expand, y1 = pos.getY() - expand, z1 = pos.getZ() - expand;
        float x2 = pos.getX() + 1f + expand, y2 = pos.getY() + 1f + expand, z2 = pos.getZ() + 1f + expand;

        quad(consumer, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quad(consumer, pose, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        quad(consumer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quad(consumer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        quad(consumer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        quad(consumer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    private static void quad(VertexConsumer consumer, Matrix4f pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              int r, int g, int b, int a) {
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        consumer.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }

    private static void line(VertexConsumer consumer, Matrix4f pose, PoseStack.Pose poseEntry,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float r, float g, float b, float a) {
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(poseEntry, 0f, 1f, 0f);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(poseEntry, 0f, 1f, 0f);
    }

    /**
     * Get the best sprite to display for a given item.
     * For AE2-style part items (export bus, import bus, etc.), the item model
     * is a composite of cable + part, and getParticleIcon() returns the cable
     * texture. This method tries looking up {namespace}:part/{path} on the
     * block atlas first, falling back to getParticleIcon().
     * ExtendedAE/AdvancedAE register parts with a _part suffix that the
     * texture path doesn't have, so we also try the stripped path.
     * Some ExtendedAE textures use _base suffix (e.g. storage buses).
     */
    private static TextureAtlasSprite getDisplaySprite(ResourceLocation itemId, BakedModel model) {
        // Only applies to AE2-compatible mods with composite part models
        String ns = itemId.getNamespace();
        if (!ns.equals("ae2") && !ns.equals("extendedae") && !ns.equals("advanced_ae") && !ns.equals("appmek")) {
            return model.getParticleIcon();
        }
        Minecraft mc = Minecraft.getInstance();
        String path = itemId.getPath();
        // Strip _part suffix used by ExtendedAE/AdvancedAE
        if (path.endsWith("_part")) {
            path = path.substring(0, path.length() - 5);
        }
        // Try exact match first
        ResourceLocation partLocation = ResourceLocation.fromNamespaceAndPath(ns, "part/" + path);
        TextureAtlasSprite partSprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(partLocation);
        if (partLocation.equals(partSprite.contents().name())) {
            return partSprite;
        }
        // Some ExtendedAE textures use _base suffix (e.g., mod_storage_bus_base.png)
        ResourceLocation baseLocation = ResourceLocation.fromNamespaceAndPath(ns, "part/" + path + "_base");
        TextureAtlasSprite baseSprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(baseLocation);
        if (baseLocation.equals(baseSprite.contents().name())) {
            return baseSprite;
        }
        return model.getParticleIcon();
    }

    private static void renderEntryItem(HighlightEntry entry, HighlightEntry.OutputItem out,
                                         PoseStack poseStack, Camera camera,
                                         MultiBufferSource bufferSource,
                                         float offsetX, float size) {
        ItemStack displayStack = new ItemStack(BuiltInRegistries.ITEM.get(out.itemId()));
        if (displayStack.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(displayStack, mc.level, mc.player, 0);
        TextureAtlasSprite sprite = getDisplaySprite(out.itemId(), model);
        VertexConsumer consumer = bufferSource.getBuffer(SPRITE_NO_DEPTH);
        renderSprite(consumer, poseStack, entry.pos(), camera, offsetX, size, sprite);
    }

    private static void renderEntryFluid(HighlightEntry entry, HighlightEntry.OutputItem out,
                                          PoseStack poseStack, Camera camera,
                                          MultiBufferSource bufferSource,
                                          float offsetX, float size) {
        Fluid fluid = BuiltInRegistries.FLUID.get(out.itemId());
        if (fluid == null) return;
        var ext = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation stillTex = ext.getStillTexture(new FluidStack(fluid, 1));
        if (stillTex == null) return;
        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(stillTex);
        int tint = ext.getTintColor(new FluidStack(fluid, 1));
        VertexConsumer consumer = bufferSource.getBuffer(TINTED_SPRITE_NO_DEPTH);
        renderTintedSprite(consumer, poseStack, entry.pos(), camera, offsetX, size, sprite, tint);
    }

    private static void renderEntryChemical(HighlightEntry entry, HighlightEntry.OutputItem out,
                                             PoseStack poseStack, Camera camera,
                                             MultiBufferSource bufferSource, Minecraft mc,
                                             float offsetX, float size) {
        Registry<Chemical> chemicalRegistry = mc.level.registryAccess().registry(MekanismAPI.CHEMICAL_REGISTRY_NAME).orElse(null);
        if (chemicalRegistry == null) return;
        Chemical chemical = chemicalRegistry.get(out.itemId());
        if (chemical == null) return;
        ResourceLocation icon = chemical.getIcon();
        if (icon == null) return;
        TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(icon);
        VertexConsumer consumer = bufferSource.getBuffer(TINTED_SPRITE_NO_DEPTH);
        renderTintedSprite(consumer, poseStack, entry.pos(), camera, offsetX, size, sprite, chemical.getTint());
    }

    private static void renderSprite(VertexConsumer consumer, PoseStack poseStack,
                                      BlockPos pos, Camera camera,
                                      float offsetX, float size,
                                      TextureAtlasSprite sprite) {
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        poseStack.mulPose(camera.rotation());
        poseStack.translate(offsetX, 0, 0);

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, -size, -size, 0).setUv(u0, v1);
        consumer.addVertex(matrix, +size, -size, 0).setUv(u1, v1);
        consumer.addVertex(matrix, +size, +size, 0).setUv(u1, v0);
        consumer.addVertex(matrix, -size, +size, 0).setUv(u0, v0);

        poseStack.popPose();
    }

    private static void renderTintedSprite(VertexConsumer consumer, PoseStack poseStack,
                                            BlockPos pos, Camera camera,
                                            float offsetX, float size,
                                            TextureAtlasSprite sprite, int argb) {
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a == 0) a = 255;

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        poseStack.mulPose(camera.rotation());
        poseStack.translate(offsetX, 0, 0);

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, -size, -size, 0).setUv(u0, v1).setColor(r, g, b, a);
        consumer.addVertex(matrix, +size, -size, 0).setUv(u1, v1).setColor(r, g, b, a);
        consumer.addVertex(matrix, +size, +size, 0).setUv(u1, v0).setColor(r, g, b, a);
        consumer.addVertex(matrix, -size, +size, 0).setUv(u0, v0).setColor(r, g, b, a);

        poseStack.popPose();
    }
}
