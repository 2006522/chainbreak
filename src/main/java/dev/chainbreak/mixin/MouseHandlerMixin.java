package dev.chainbreak.mixin;

import dev.chainbreak.ChainBreakState;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 按住连锁键 + 固定 Shift 键时，用鼠标滚轮切换形状（对标 FTB Ultimine 的交互）。
 * Shift 用物理按键检测（InputConstants），不随玩家自定义的潜行键变化。
 * 只在客户端注册（client mixins）。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void chainbreak$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (!ChainBreakState.keyDown || yOffset == 0.0D) {
            return;
        }
        if (!InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                && !InputConstants.isKeyDown(InputConstants.KEY_RSHIFT)) {
            return;
        }
        // 滚轮向上 = 上一个形状，向下 = 下一个
        ChainBreakState.cycleShape(yOffset > 0.0D ? -1 : 1);
        ci.cancel();
    }
}
