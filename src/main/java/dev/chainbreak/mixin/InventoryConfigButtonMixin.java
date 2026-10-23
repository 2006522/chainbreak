package dev.chainbreak.mixin;

import dev.chainbreak.client.ChainBreakConfigMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在背包 / 创造物品栏左上角加配置按钮（对标 FTB 侧边栏按钮）。
 * addRenderableWidget 定义在 Screen 父类里，@Shadow 只查目标类本身，所以用反射调用。
 * 只在 client mixins 注册（引用了客户端类）。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryConfigButtonMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void chainbreak$addConfigButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!(self instanceof InventoryScreen) && !(self instanceof CreativeModeInventoryScreen)) {
            return;
        }
        Button button = Button.builder(Component.literal("⚙"), b ->
                        Minecraft.getInstance().setScreenAndShow(new ChainBreakConfigMenu(self)))
                .bounds(4, 4, 20, 20).build();
        try {
            Method m = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(self, button);
        } catch (Exception ignored) {
            // 反射失败时按钮不加入，避免崩溃
        }
    }
}
