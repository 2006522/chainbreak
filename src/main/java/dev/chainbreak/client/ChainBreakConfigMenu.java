package dev.chainbreak.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 配置主菜单（对标 FTB Library 的配置入口）：
 * 连锁挖矿 → 客户端配置 / 服务端配置 / 返回。
 */
public class ChainBreakConfigMenu extends Screen {

    private final Screen parent;

    public ChainBreakConfigMenu(Screen parent) {
        super(Component.literal("连锁挖矿"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 44;

        addRenderableWidget(Button.builder(Component.literal("客户端配置"), b ->
                        this.minecraft.setScreenAndShow(new ChainBreakConfigListScreen(false, this)))
                .bounds(cx - 60, y, 120, 20).build());

        addRenderableWidget(Button.builder(Component.literal("服务端配置"), b ->
                        this.minecraft.setScreenAndShow(new ChainBreakConfigListScreen(true, this)))
                .bounds(cx - 60, y + 24, 120, 20).build());

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(cx - 60, y + 48, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g) {
        super.extractRenderState(g);
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 62, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
