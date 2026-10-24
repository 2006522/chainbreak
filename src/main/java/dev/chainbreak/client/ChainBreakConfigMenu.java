package dev.chainbreak.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 配置入口（保留这个薄壳是为了两处调用点不用改）。
 *
 * <p>以前这里是个「客户端配置 / 服务端配置 / 返回」的二级菜单，现在两类设置已经合成一页，
 * 所以直接打开 {@link ChainBreakConfigListScreen}，parent 原样传下去。</p>
 */
public final class ChainBreakConfigMenu {

    private ChainBreakConfigMenu() {
    }

    /** 打开设置界面；{@code parent} 为 null 表示从游戏里直接打开（关闭时回到游戏）。 */
    public static void open(Screen parent) {
        Minecraft.getInstance().setScreenAndShow(new ChainBreakConfigListScreen(parent));
    }
}