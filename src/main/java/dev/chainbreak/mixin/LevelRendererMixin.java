package dev.chainbreak.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.chainbreak.ChainBreakState;
import dev.chainbreak.client.ChainBreakPreview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 按住连锁键时，为准星指向方块按当前形状将被连锁挖掉的所有方块
 * 提交线框轮廓（复用原版 submitHitOutline 管线，白色线框）。
 * 只在 client mixins 注册（引用了客户端类）。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    /** 复用原版的轮廓提交方法（private，用 @Shadow 假体引用）。 */
    @Shadow
    private void submitHitOutline(PoseStack poseStack, SubmitNodeCollector collector,
                                  net.minecraft.client.renderer.rendertype.RenderType renderType,
                                  BlockOutlineRenderState state, int color, float lineWidth,
                                  boolean translucent) {
        throw new AssertionError();
    }

    @Inject(method = "submitBlockOutline", at = @At("TAIL"))
    private void chainbreak$submitChainOutlines(PoseStack poseStack, SubmitNodeCollector collector,
                                                LevelRenderState levelState, CallbackInfo ci) {
        if (!ChainBreakState.keyDown) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || levelState.cameraRenderState == null) {
            return;
        }
        // 目标是"起始方块 + 连锁部分"：准星那一格也要画框，
        // 否则看起来像没被选中（原版自己的轮廓是很细的暗色框，暗处几乎看不见）
        List<BlockPos> targets = ChainBreakPreview.outlineTargets(mc);
        if (targets == null || targets.isEmpty()) {
            return;
        }

        var cam = levelState.cameraRenderState.pos;
        int color = 0xFFFFFFFF; // 白色线框

        for (BlockPos p : targets) {
            BlockState s = mc.level.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            VoxelShape shape = s.getShape(mc.level, p);
            if (shape.isEmpty()) {
                continue;
            }
            BlockOutlineRenderState st = new BlockOutlineRenderState(p.immutable(), false, false, shape);
            poseStack.pushPose();
            poseStack.translate(p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z);
            this.submitHitOutline(poseStack, collector, RenderTypes.lines(), st, color, 1.0F, false);
            poseStack.popPose();
        }
    }
}
