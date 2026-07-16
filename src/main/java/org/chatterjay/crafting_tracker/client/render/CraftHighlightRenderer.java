package org.chatterjay.crafting_tracker.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.client.ClientHighlightCache;
import org.chatterjay.crafting_tracker.client.ClientLocatorCache;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData.HighlightEntry;
import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights.LocatorHit;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = Crafting_tracker.MODID, value = Dist.CLIENT)
public class CraftHighlightRenderer {

    private static final int TYPE_CHEMICAL = 3;
    private static final int MAX_OUTPUTS = 4;
    private static final int LOCATOR_COLOR = 0x78F7FF;
    private static final int PANEL_COLOR = 0x06080B;

    /** Get the highlight color for a provider status ordinal (reads from config). */
    private static int getProviderColor(int ordinal) {
        return switch (ordinal) {
            case 0 -> CTConfig.colorActive;
            case 1 -> CTConfig.colorStalled;
            case 2 -> CTConfig.colorStuck;
            default -> 0xFFFFFF;
        };
    }

    @SuppressWarnings("deprecation")
    private static final RenderType SOLID_COLOR_NO_DEPTH = RenderType.create(
            "ct_solid_color_no_depth",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            512, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            () -> net.minecraft.client.renderer.GameRenderer.getPositionColorShader()))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", 519))
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                            "translucent",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.blendFuncSeparate(
                                        GlStateManager.SourceFactor.SRC_ALPHA,
                                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                                        GlStateManager.SourceFactor.ONE,
                                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
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
        var locatorHits = ClientLocatorCache.INSTANCE.getActiveHits();

        if (highlights.isEmpty() && locatorHits.isEmpty()) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        Font font = mc.font;

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        PoseStack.Pose poseEntry = poseStack.last();
        Matrix4f poseMatrix = poseEntry.pose();

        VertexConsumer outlineConsumer = bufferSource.getBuffer(SOLID_COLOR_NO_DEPTH);
        for (HighlightEntry entry : highlights) {
            int color = getProviderColor(entry.statusOrdinal());
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            renderModelOutline(mc, outlineConsumer, poseMatrix, entry.pos(), r, g, b,
                    providerOutlineAlpha(entry.statusOrdinal()), providerThickness(entry.statusOrdinal()));
        }
        for (BlockPos pos : locatorHits.keySet()) {
            renderModelOutline(mc, outlineConsumer, poseMatrix, pos, 0xF0, 0xFB, 0xFF, 105, 0.020f);
            renderModelOutline(mc, outlineConsumer, poseMatrix, pos, 0x78, 0xF7, 0xFF, 230, 0.011f);
        }
        bufferSource.endBatch(SOLID_COLOR_NO_DEPTH);

        for (HighlightEntry entry : highlights) {
            List<HighlightEntry.OutputItem> outputs = entry.outputs();
            if (outputs == null || outputs.isEmpty()) continue;
            int count = Math.min(outputs.size(), MAX_OUTPUTS);
            float spriteSize = 0.22f;
            float spacing = (count <= 1) ? 0f : 0.23f;
            float startX = -(count - 1) * spacing / 2f;

            for (int i = 0; i < count; i++) {
                HighlightEntry.OutputItem out = outputs.get(i);
                int outputType = out.outputType();

                if (outputType == 0) {
                    renderEntryItem(entry.pos(), out, poseStack, camera, bufferSource, startX + i * spacing, spriteSize);
                } else if (outputType == 1) {
                    renderEntryFluid(entry.pos(), out, poseStack, camera, bufferSource, startX + i * spacing, spriteSize);
                } else if (outputType == TYPE_CHEMICAL) {
                    renderEntryChemical(entry.pos(), out, poseStack, camera, bufferSource, mc, startX + i * spacing, spriteSize);
                }
            }
        }

        for (var entry : locatorHits.entrySet()) {
            BlockPos pos = entry.getKey();
            List<LocatorHit> hits = entry.getValue();
            if (hits == null || hits.isEmpty()) continue;
            int count = Math.min(hits.size(), MAX_OUTPUTS);
            float spriteSize = 0.20f;
            float spacing = (count <= 1) ? 0f : 0.22f;
            float startX = -(count - 1) * spacing / 2f;

            for (int i = 0; i < count; i++) {
                LocatorHit hit = hits.get(i);
                int outputType = hit.outputType();

                if (outputType == 0) {
                    renderLocatorItem(pos, hit, poseStack, camera, bufferSource, startX + i * spacing, spriteSize);
                } else if (outputType == TYPE_CHEMICAL) {
                    renderLocatorChemical(pos, hit, poseStack, camera, bufferSource, mc, startX + i * spacing, spriteSize);
                }
            }
        }
        bufferSource.endBatch(SPRITE_NO_DEPTH);
        bufferSource.endBatch(TINTED_SPRITE_NO_DEPTH);

        Set<BlockPos> providerLabelPositions = new HashSet<>();
        for (HighlightEntry entry : highlights) {
            providerLabelPositions.add(entry.pos());
            renderProviderLabel(mc, poseStack, font, bufferSource, camera, entry);
        }
        for (var entry : locatorHits.entrySet()) {
            if (providerLabelPositions.contains(entry.getKey())) continue;
            renderLocatorLabel(mc, poseStack, font, bufferSource, camera, entry.getKey(), entry.getValue().size());
        }
        bufferSource.endBatch(SOLID_COLOR_NO_DEPTH);
        bufferSource.endBatch();

        poseStack.popPose();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static int providerOutlineAlpha(int ordinal) {
        int base = Math.max(0, Math.min(255, CTConfig.outlineAlpha));
        return switch (ordinal) {
            case 1 -> Math.min(255, base + 10);
            case 2 -> 255;
            default -> base;
        };
    }

    private static float providerThickness(int ordinal) {
        return switch (ordinal) {
            case 1 -> 0.018f;
            case 2 -> 0.024f;
            default -> 0.014f;
        };
    }

    private static void renderProviderLabel(Minecraft mc, PoseStack poseStack, Font font,
                                            MultiBufferSource.BufferSource bufferSource,
                                            Camera camera, HighlightEntry entry) {
        int color = getProviderColor(entry.statusOrdinal());
        String line1 = statusLabel(entry.statusOrdinal());
        int outputs = entry.outputs() == null ? 0 : entry.outputs().size();
        String line2 = Component.translatable("overlay.crafting_tracker.outputs", outputs).getString();
        renderBadge(mc, poseStack, font, bufferSource, camera, entry.pos(),
                line1, line2, color, providerOutlineAlpha(entry.statusOrdinal()));
    }

    private static void renderLocatorLabel(Minecraft mc, PoseStack poseStack, Font font,
                                           MultiBufferSource.BufferSource bufferSource,
                                           Camera camera, BlockPos pos, int hits) {
        String line2 = Component.translatable("overlay.crafting_tracker.matches", hits).getString();
        renderBadge(mc, poseStack, font, bufferSource, camera, pos,
                Component.translatable("overlay.crafting_tracker.locator").getString(), line2, LOCATOR_COLOR, 230);
    }

    private static void renderBadge(Minecraft mc, PoseStack poseStack, Font font,
                                    MultiBufferSource.BufferSource bufferSource,
                                    Camera camera, BlockPos pos,
                                    String line1, String line2, int rgb, int alpha) {
        int maxWidth = 92;
        String top = trimToWidth(font, line1, maxWidth);
        String bottomText = trimToWidth(font, line2, maxWidth);
        float labelWidth = Math.max(font.width(top), font.width(bottomText)) + 10f;
        float labelHeight = font.lineHeight * 2 + 8f;

        AABB bounds = getCombinedShapeBounds(mc, pos);
        double labelY = pos.getY() + bounds.maxY + 0.95;
        double distance = camera.getPosition().distanceTo(Vec3.atCenterOf(pos));
        float scale = (float) Math.max(0.016, Math.min(0.026, distance * 0.0012 + 0.016));

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, labelY, pos.getZ() + 0.5);
        poseStack.mulPose(camera.rotation());
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(-scale, -scale, scale);

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer background = bufferSource.getBuffer(SOLID_COLOR_NO_DEPTH);
        float left = -labelWidth / 2f;
        float right = labelWidth / 2f;
        float topY = -4f;
        float bottomY = labelHeight - 4f;
        int panelAlpha = Math.max(75, Math.min(180, CTConfig.badgeBackgroundAlpha + 75));
        int accentAlpha = Math.max(130, Math.min(255, CTConfig.badgeAccentAlpha + 120));

        quad4(background, matrix, left, topY, 0f, left, bottomY, 0f, right, bottomY, 0f, right, topY, 0f,
                (PANEL_COLOR >> 16) & 0xFF, (PANEL_COLOR >> 8) & 0xFF, PANEL_COLOR & 0xFF, panelAlpha);
        quad4(background, matrix, left, bottomY - 1.2f, -0.01f, left, bottomY, -0.01f,
                right, bottomY, -0.01f, right, bottomY - 1.2f, -0.01f,
                (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.min(alpha, accentAlpha));

        int textColor = (0xF0 << 24) | rgb;
        int secondaryColor = 0xD8DDE6 | (0xDD << 24);
        font.drawInBatch(top, -font.width(top) / 2f, 0f,
                textColor, false, matrix, bufferSource,
                Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        font.drawInBatch(bottomText, -font.width(bottomText) / 2f, font.lineHeight + 2,
                secondaryColor, false, matrix, bufferSource,
                Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);

        poseStack.popPose();
    }

    private static String statusLabel(int ordinal) {
        return switch (ordinal) {
            case 0 -> Component.translatable("overlay.crafting_tracker.status.active").getString();
            case 1 -> Component.translatable("overlay.crafting_tracker.status.stalled").getString();
            case 2 -> Component.translatable("overlay.crafting_tracker.status.stuck").getString();
            default -> Component.translatable("overlay.crafting_tracker.status.crafting").getString();
        };
    }

    private static void renderModelOutline(Minecraft mc, VertexConsumer consumer, Matrix4f pose,
                                           BlockPos pos, int r, int g, int b, int a, float thickness) {
        AABB worldBox = getCombinedShapeBounds(mc, pos).move(pos).inflate(0.003);
        renderBoxOutlineBars(consumer, pose, worldBox, r, g, b, a, thickness);
    }

    private static List<AABB> getShapeBoxes(Minecraft mc, BlockPos pos) {
        if (mc.level == null) {
            return List.of(new AABB(0, 0, 0, 1, 1, 1));
        }
        BlockState state = mc.level.getBlockState(pos);
        VoxelShape shape = state.getShape(mc.level, pos);
        List<AABB> boxes = shape.toAabbs();
        return boxes.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : boxes;
    }

    private static AABB getCombinedShapeBounds(Minecraft mc, BlockPos pos) {
        AABB combined = null;
        for (AABB box : getShapeBoxes(mc, pos)) {
            combined = combined == null ? box : combined.minmax(box);
        }
        return combined == null ? new AABB(0, 0, 0, 1, 1, 1) : combined;
    }

    private static void renderBoxOutlineBars(VertexConsumer consumer, Matrix4f pose, AABB box,
                                             int r, int g, int b, int a, float thickness) {
        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float z1 = (float) box.minZ;
        float x2 = (float) box.maxX;
        float y2 = (float) box.maxY;
        float z2 = (float) box.maxZ;

        bar(consumer, pose, x1, y1, z1, x2, y1, z1, thickness, 'x', r, g, b, a);
        bar(consumer, pose, x1, y1, z2, x2, y1, z2, thickness, 'x', r, g, b, a);
        bar(consumer, pose, x1, y2, z1, x2, y2, z1, thickness, 'x', r, g, b, a);
        bar(consumer, pose, x1, y2, z2, x2, y2, z2, thickness, 'x', r, g, b, a);

        bar(consumer, pose, x1, y1, z1, x1, y2, z1, thickness, 'y', r, g, b, a);
        bar(consumer, pose, x1, y1, z2, x1, y2, z2, thickness, 'y', r, g, b, a);
        bar(consumer, pose, x2, y1, z1, x2, y2, z1, thickness, 'y', r, g, b, a);
        bar(consumer, pose, x2, y1, z2, x2, y2, z2, thickness, 'y', r, g, b, a);

        bar(consumer, pose, x1, y1, z1, x1, y1, z2, thickness, 'z', r, g, b, a);
        bar(consumer, pose, x1, y2, z1, x1, y2, z2, thickness, 'z', r, g, b, a);
        bar(consumer, pose, x2, y1, z1, x2, y1, z2, thickness, 'z', r, g, b, a);
        bar(consumer, pose, x2, y2, z1, x2, y2, z2, thickness, 'z', r, g, b, a);
    }

    private static void bar(VertexConsumer consumer, Matrix4f pose,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            float thickness, char axis, int r, int g, int b, int a) {
        float minX = Math.min(x1, x2);
        float minY = Math.min(y1, y2);
        float minZ = Math.min(z1, z2);
        float maxX = Math.max(x1, x2);
        float maxY = Math.max(y1, y2);
        float maxZ = Math.max(z1, z2);

        if (axis != 'x') {
            minX -= thickness;
            maxX += thickness;
        }
        if (axis != 'y') {
            minY -= thickness;
            maxY += thickness;
        }
        if (axis != 'z') {
            minZ -= thickness;
            maxZ += thickness;
        }

        box(consumer, pose, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    private static void box(VertexConsumer consumer, Matrix4f pose,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            int r, int g, int b, int a) {
        quad4(consumer, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quad4(consumer, pose, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        quad4(consumer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quad4(consumer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        quad4(consumer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        quad4(consumer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    private static void quad4(VertexConsumer consumer, Matrix4f pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              int r, int g, int b, int a) {
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        consumer.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
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
        String ns = itemId.getNamespace();
        if (!ns.equals("ae2") && !ns.equals("extendedae") && !ns.equals("advanced_ae") && !ns.equals("appmek")) {
            return model.getParticleIcon();
        }
        Minecraft mc = Minecraft.getInstance();
        String path = itemId.getPath();
        if (path.endsWith("_part")) {
            path = path.substring(0, path.length() - 5);
        }
        ResourceLocation partLocation = ResourceLocation.fromNamespaceAndPath(ns, "part/" + path);
        TextureAtlasSprite partSprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(partLocation);
        if (partLocation.equals(partSprite.contents().name())) {
            return partSprite;
        }
        ResourceLocation baseLocation = ResourceLocation.fromNamespaceAndPath(ns, "part/" + path + "_base");
        TextureAtlasSprite baseSprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(baseLocation);
        if (baseLocation.equals(baseSprite.contents().name())) {
            return baseSprite;
        }
        return model.getParticleIcon();
    }

    private static void renderEntryItem(BlockPos pos, HighlightEntry.OutputItem out,
                                        PoseStack poseStack, Camera camera,
                                        MultiBufferSource bufferSource,
                                        float offsetX, float size) {
        ItemStack displayStack = new ItemStack(BuiltInRegistries.ITEM.get(out.itemId()));
        if (displayStack.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(displayStack, mc.level, mc.player, 0);
        TextureAtlasSprite sprite = getDisplaySprite(out.itemId(), model);
        VertexConsumer consumer = bufferSource.getBuffer(SPRITE_NO_DEPTH);
        renderSprite(consumer, poseStack, pos, camera, offsetX, size, sprite);
    }

    private static void renderEntryFluid(BlockPos pos, HighlightEntry.OutputItem out,
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
        renderTintedSprite(consumer, poseStack, pos, camera, offsetX, size, sprite, tint);
    }

    private static void renderEntryChemical(BlockPos pos, HighlightEntry.OutputItem out,
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
        renderTintedSprite(consumer, poseStack, pos, camera, offsetX, size, sprite, chemical.getTint());
    }

    private static void renderLocatorItem(BlockPos pos, LocatorHit hit,
                                          PoseStack poseStack, Camera camera,
                                          MultiBufferSource bufferSource,
                                          float offsetX, float size) {
        ItemStack displayStack = new ItemStack(BuiltInRegistries.ITEM.get(hit.itemId()));
        if (displayStack.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(displayStack, mc.level, mc.player, 0);
        TextureAtlasSprite sprite = getDisplaySprite(hit.itemId(), model);
        VertexConsumer consumer = bufferSource.getBuffer(SPRITE_NO_DEPTH);
        renderSprite(consumer, poseStack, pos, camera, offsetX, size, sprite);
    }

    private static void renderLocatorChemical(BlockPos pos, LocatorHit hit,
                                              PoseStack poseStack, Camera camera,
                                              MultiBufferSource bufferSource, Minecraft mc,
                                              float offsetX, float size) {
        if (mc.level == null) return;
        Registry<Chemical> chemicalRegistry = mc.level.registryAccess().registry(MekanismAPI.CHEMICAL_REGISTRY_NAME).orElse(null);
        if (chemicalRegistry == null) return;
        Chemical chemical = chemicalRegistry.get(hit.itemId());
        if (chemical == null) return;
        ResourceLocation icon = chemical.getIcon();
        if (icon == null) return;
        TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(icon);
        VertexConsumer consumer = bufferSource.getBuffer(TINTED_SPRITE_NO_DEPTH);
        renderTintedSprite(consumer, poseStack, pos, camera, offsetX, size, sprite, chemical.getTint());
    }

    private static void renderSprite(VertexConsumer consumer, PoseStack poseStack,
                                     BlockPos pos, Camera camera,
                                     float offsetX, float size,
                                     TextureAtlasSprite sprite) {
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        Minecraft mc = Minecraft.getInstance();
        double iconY = pos.getY() + getCombinedShapeBounds(mc, pos).maxY + 0.18;

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, iconY, pos.getZ() + 0.5);
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
        Minecraft mc = Minecraft.getInstance();
        double iconY = pos.getY() + getCombinedShapeBounds(mc, pos).maxY + 0.18;

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, iconY, pos.getZ() + 0.5);
        poseStack.mulPose(camera.rotation());
        poseStack.translate(offsetX, 0, 0);

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, -size, -size, 0).setUv(u0, v1).setColor(r, g, b, a);
        consumer.addVertex(matrix, +size, -size, 0).setUv(u1, v1).setColor(r, g, b, a);
        consumer.addVertex(matrix, +size, +size, 0).setUv(u1, v0).setColor(r, g, b, a);
        consumer.addVertex(matrix, -size, +size, 0).setUv(u0, v0).setColor(r, g, b, a);

        poseStack.popPose();
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int targetWidth = Math.max(0, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, targetWidth) + ellipsis;
    }
}
