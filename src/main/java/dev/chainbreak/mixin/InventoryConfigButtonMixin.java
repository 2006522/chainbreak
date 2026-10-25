package dev.chainbreak.mixin;

import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.client.ChainBreakConfigMenu;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * 在背包 / 创造物品栏里加配置按钮（对标 FTB 侧边栏按钮），并支持**按住拖动**改位置。
 *
 * <p>位置存在配置里（{@code button_x} / {@code button_y}）。轻点打开配置，拖动只挪位置，
 * 两者靠"移动是否超过 2px"区分。</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryConfigButtonMixin {

    @Unique
    private static final int CHAINBREAK_WIDTH = 20;
    @Unique
    private static final int CHAINBREAK_HEIGHT = 20;
    @Unique
    private static final int CHAINBREAK_DRAG_THRESHOLD = 2;

    @Unique
    private static Button chainbreak$button;
    @Unique
    private static Screen chainbreak$screen;
    @Unique
    private static boolean chainbreak$dragging;
    @Unique
    private static boolean chainbreak$moved;
    @Unique
    private static double chainbreak$grabX;
    @Unique
    private static double chainbreak$grabY;
    @Unique
    private static double chainbreak$startX;
    @Unique
    private static double chainbreak$startY;

    @Inject(method = "init", at = @At("TAIL"))
    private void chainbreak$addConfigButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        chainbreak$button = null;
        chainbreak$screen = self;
        chainbreak$dragging = false;
        chainbreak$moved = false;
        if (!(self instanceof InventoryScreen) && !(self instanceof CreativeModeInventoryScreen)) {
            return;
        }
        ChainBreakConfig.loadIfNeeded();

        Button button = Button.builder(Component.literal("⚙"), b -> {
                    // 这个回调只在"没被拖动"的轻点时触发
                    ChainBreakConfigMenu.open(self);
                })
                .bounds(ChainBreakConfig.buttonX, ChainBreakConfig.buttonY, CHAINBREAK_WIDTH, CHAINBREAK_HEIGHT)
                .build();
        chainbreak$clamp(button);
        // 位置是按屏幕绝对坐标存的，换分辨率 / 界面缩放后可能落到屏幕外，看着像"没保存"。
        // 这里被收掉的话就把收回后的值写回去，免得每次进界面都在"存的值"和"实际显示"之间来回跳。
        if (button.getX() != ChainBreakConfig.buttonX || button.getY() != ChainBreakConfig.buttonY) {
            ChainBreakConfig.buttonX = button.getX();
            ChainBreakConfig.buttonY = button.getY();
            ChainBreakConfig.save();
        }
        try {
            Method m = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(self, button);
            chainbreak$button = button;
        } catch (Exception ignored) {
            // 反射失败时按钮不加入，避免崩溃
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void chainbreak$onMouseClicked(MouseButtonEvent event, boolean doubled,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (chainbreak$button == null || chainbreak$screen != (Object) this || !event.isLeftMouse()) {
            return;
        }
        if (!chainbreak$button.isMouseOver(event.x(), event.y())) {
            return;
        }
        chainbreak$dragging = true;
        chainbreak$moved = false;
        chainbreak$grabX = event.x() - chainbreak$button.getX();
        chainbreak$grabY = event.y() - chainbreak$button.getY();
        chainbreak$startX = event.x();
        chainbreak$startY = event.y();
        cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void chainbreak$onMouseDragged(MouseButtonEvent event, double deltaX, double deltaY,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!chainbreak$dragging || chainbreak$button == null) {
            return;
        }
        if (Math.abs(event.x() - chainbreak$startX) > CHAINBREAK_DRAG_THRESHOLD
                || Math.abs(event.y() - chainbreak$startY) > CHAINBREAK_DRAG_THRESHOLD) {
            chainbreak$moved = true;
        }
        chainbreak$button.setX((int) Math.round(event.x() - chainbreak$grabX));
        chainbreak$button.setY((int) Math.round(event.y() - chainbreak$grabY));
        chainbreak$clamp(chainbreak$button);
        cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void chainbreak$onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!chainbreak$dragging || chainbreak$button == null) {
            return;
        }
        chainbreak$dragging = false;
        if (chainbreak$moved) {
            ChainBreakConfig.buttonX = chainbreak$button.getX();
            ChainBreakConfig.buttonY = chainbreak$button.getY();
            ChainBreakConfig.save();
        } else {
            ChainBreakConfigMenu.open(chainbreak$screen);
        }
        cir.setReturnValue(Boolean.TRUE);
    }

    @Unique
    private static void chainbreak$clamp(Button button) {
        int maxX = Math.max(0, chainbreak$screen.width - CHAINBREAK_WIDTH);
        int maxY = Math.max(0, chainbreak$screen.height - CHAINBREAK_HEIGHT);
        int x = Math.max(0, Math.min(maxX, button.getX()));
        int y = Math.max(0, Math.min(maxY, button.getY()));
        button.setX(x);
        button.setY(y);
    }
}
