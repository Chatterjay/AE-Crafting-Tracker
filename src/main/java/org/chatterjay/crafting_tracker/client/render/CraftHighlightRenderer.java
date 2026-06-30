package org.chatterjay.crafting_tracker.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

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
import net.minecraft.world.item.Items;
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

        // Item sprite pass: billboard sprite in center of each provider
        VertexConsumer spriteConsumer = bufferSource.getBuffer(SPRITE_NO_DEPTH);
        for (HighlightEntry entry : highlights) {
            if (entry.itemId() == null || entry.outputType() != 0) continue;
            ItemStack displayStack = new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()));
            if (displayStack.isEmpty()) continue;
            BakedModel model = mc.getItemRenderer().getModel(displayStack, mc.level, mc.player, 0);
            TextureAtlasSprite sprite = model.getParticleIcon();
            renderItemSprite(spriteConsumer, poseStack, entry.pos(), camera, sprite);
        }
        bufferSource.endBatch(SPRITE_NO_DEPTH);

        // Fluid sprite pass: billboard with tint in center of each provider
        VertexConsumer fluidConsumer = bufferSource.getBuffer(TINTED_SPRITE_NO_DEPTH);
        for (HighlightEntry entry : highlights) {
            if (entry.itemId() == null || entry.outputType() != 1) continue;
            Fluid fluid = BuiltInRegistries.FLUID.get(entry.itemId());
            if (fluid == null) continue;
            var ext = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation stillTex = ext.getStillTexture(new FluidStack(fluid, 1));
            if (stillTex == null) continue;
            TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(stillTex);
            int tint = ext.getTintColor(new FluidStack(fluid, 1));
            renderTintedSprite(fluidConsumer, poseStack, entry.pos(), camera, sprite, tint);
        }
        bufferSource.endBatch(TINTED_SPRITE_NO_DEPTH);

        // Chemical sprite pass: billboard with tint in center of each provider
        VertexConsumer chemicalConsumer = bufferSource.getBuffer(TINTED_SPRITE_NO_DEPTH);
        for (HighlightEntry entry : highlights) {
            if (entry.itemId() == null || entry.outputType() != TYPE_CHEMICAL) continue;
            Registry<Chemical> chemicalRegistry = mc.level.registryAccess().registry(MekanismAPI.CHEMICAL_REGISTRY_NAME).orElse(null);
            if (chemicalRegistry == null) continue;
            Chemical chemical = chemicalRegistry.get(entry.itemId());
            if (chemical == null) continue;
            ResourceLocation icon = chemical.getIcon();
            if (icon == null) continue;
            TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(icon);
            renderTintedSprite(chemicalConsumer, poseStack, entry.pos(), camera, sprite, chemical.getTint());
        }
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

    private static void renderItemSprite(VertexConsumer consumer, PoseStack poseStack,
                                          BlockPos pos, Camera camera, TextureAtlasSprite sprite) {
        float s = 0.35f;
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        poseStack.mulPose(camera.rotation());

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, -s, -s, 0).setUv(u0, v1);
        consumer.addVertex(matrix, +s, -s, 0).setUv(u1, v1);
        consumer.addVertex(matrix, +s, +s, 0).setUv(u1, v0);
        consumer.addVertex(matrix, -s, +s, 0).setUv(u0, v0);

        poseStack.popPose();
    }

    private static void renderTintedSprite(VertexConsumer consumer, PoseStack poseStack,
                                            BlockPos pos, Camera camera, TextureAtlasSprite sprite,
                                            int argb) {
        float s = 0.35f;
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a == 0) a = 255; // ensure visibility

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        poseStack.mulPose(camera.rotation());

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, -s, -s, 0).setUv(u0, v1).setColor(r, g, b, a);
        consumer.addVertex(matrix, +s, -s, 0).setUv(u1, v1).setColor(r, g, b, a);
        consumer.addVertex(matrix, +s, +s, 0).setUv(u1, v0).setColor(r, g, b, a);
        consumer.addVertex(matrix, -s, +s, 0).setUv(u0, v0).setColor(r, g, b, a);

        poseStack.popPose();
    }
}
