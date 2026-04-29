package com.wnir;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * SpecialModelRenderer for Blue Sticky Tape (filled state).
 *
 * Renders a flat 2D quad using a face-selected sprite from the stored block's
 * model, then overlays a blue X cross quad.
 *
 * Face priority: SOUTH → UP → first unculled quad → particleIcon().
 * SOUTH gives the front face for blocks like furnace/chest (most distinctive).
 * UP gives the top face for grass.  Unculled covers cross/flower models.
 */
@OnlyIn(Dist.CLIENT)
public class BlueStickyTapeRenderer implements SpecialModelRenderer<BlockState> {

    public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

    private static final Identifier CROSS_TEXTURE_ID =
        WnirRegistries.id("dynamic/blue_sticky_tape_cross");

    private static @Nullable RenderType crossRenderType = null;

    private BlueStickyTapeRenderer() {}

    // -------------------------------------------------------------------------
    // SpecialModelRenderer interface
    // -------------------------------------------------------------------------

    @Override
    public @Nullable BlockState extractArgument(ItemStack stack) {
        if (!BlueStickyTapeItem.hasStoredBlock(stack)) return null;
        return BlueStickyTapeItem.getStoredStateClient(stack);
    }

    @Override
    public void submit(
        @Nullable BlockState state,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int packedLight,
        int packedOverlay,
        boolean foilEnabled,
        int tint
    ) {
        if (state == null) return;

        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite sprite = getBestSprite(state, mc);

        Identifier atlasId = sprite.atlasLocation();
        RenderType blockRt = RenderTypes.entityTranslucentCullItemTarget(atlasId);

        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        // Flat quad at z=0.5, full 1×1 face
        collector.submitCustomGeometry(poseStack, blockRt, (pose, vc) -> {
            vc.addVertex(pose, 0f, 0f, 0.5f).setColor(255,255,255,255).setUv(u0,v1).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
            vc.addVertex(pose, 1f, 0f, 0.5f).setColor(255,255,255,255).setUv(u1,v1).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
            vc.addVertex(pose, 1f, 1f, 0.5f).setColor(255,255,255,255).setUv(u1,v0).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
            vc.addVertex(pose, 0f, 1f, 0.5f).setColor(255,255,255,255).setUv(u0,v0).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
        });

        // Blue cross overlay
        RenderType crossRt = getCrossRenderType();
        if (crossRt == null) return;

        collector.submitCustomGeometry(poseStack, crossRt, (pose, vc) -> {
            vc.addVertex(pose, 0f, 0f, 0.502f).setColor(255,255,255,230).setUv(0f,1f).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
            vc.addVertex(pose, 1f, 0f, 0.502f).setColor(255,255,255,230).setUv(1f,1f).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
            vc.addVertex(pose, 1f, 1f, 0.502f).setColor(255,255,255,230).setUv(1f,0f).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
            vc.addVertex(pose, 0f, 1f, 0.502f).setColor(255,255,255,230).setUv(0f,0f).setOverlay(packedOverlay).setLight(packedLight).setNormal(0f,0f,1f);
        });
    }

    @Override
    public void getExtents(Consumer<Vector3fc> consumer) {
        consumer.accept(new Vector3f(0f, 0f, 0f));
        consumer.accept(new Vector3f(1f, 1f, 1f));
    }

    // -------------------------------------------------------------------------
    // Sprite selection
    // -------------------------------------------------------------------------

    /**
     * Picks the most informative sprite for the stored block:
     *   1. SOUTH face (shows furnace/chest fronts)
     *   2. UP face    (shows grass top)
     *   3. First unculled quad (handles cross / flower models)
     *   4. particleMaterial() fallback
     */
    private static TextureAtlasSprite getBestSprite(BlockState state, Minecraft mc) {
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
        RandomSource rng = RandomSource.create(42L);

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(rng, parts);

        for (Direction dir : new Direction[]{ Direction.SOUTH, Direction.UP }) {
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(dir);
                if (!quads.isEmpty()) return quads.get(0).materialInfo().sprite();
            }
        }
        // unculled quads (cross / flower models, torches, etc.)
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(null);
            if (!quads.isEmpty()) return quads.get(0).materialInfo().sprite();
        }

        return model.particleMaterial().sprite();
    }

    // -------------------------------------------------------------------------
    // Blue cross overlay (generated once, cached)
    // -------------------------------------------------------------------------

    private static @Nullable RenderType getCrossRenderType() {
        if (crossRenderType != null) return crossRenderType;

        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 16, 16, false);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                img.setPixelABGR(x, y, 0);

        int BLUE = 0xFFFF501E; // ABGR: A=255, B=255, G=80, R=30
        for (int i = 0; i < 16; i++) {
            setWide(img, i,      i,      BLUE);
            setWide(img, 15 - i, i,      BLUE);
        }

        Minecraft.getInstance().getTextureManager()
            .register(CROSS_TEXTURE_ID, new DynamicTexture(() -> "blue_sticky_tape_cross", img));
        crossRenderType = RenderTypes.entityTranslucentCullItemTarget(CROSS_TEXTURE_ID);
        return crossRenderType;
    }

    private static void setWide(NativeImage img, int x, int y, int color) {
        img.setPixelABGR(x, y, color);
        if (x + 1 < 16) img.setPixelABGR(x + 1, y, color);
    }

    // -------------------------------------------------------------------------
    // Unbaked codec
    // -------------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    public static final class Unbaked implements SpecialModelRenderer.Unbaked<BlockState> {
        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<BlockState>> type() { return MAP_CODEC; }

        @Override
        public SpecialModelRenderer<BlockState> bake(SpecialModelRenderer.BakingContext ctx) {
            return new BlueStickyTapeRenderer();
        }
    }
}
