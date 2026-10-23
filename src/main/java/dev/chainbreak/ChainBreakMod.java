package dev.chainbreak;

import dev.chainbreak.command.ChainBreakCommand;

import net.fabricmc.api.ModInitializer;

/**
 * 通用入口（客户端与服务端都会加载）：初始化配置并注册命令。
 */
public class ChainBreakMod implements ModInitializer {

    @Override
    public void onInitialize() {
        ChainBreakConfig.loadIfNeeded();
        ChainBreakCommand.register();
        ChainBreak.LOGGER.log(System.Logger.Level.INFO,
                "[chainbreak] 已加载，形状=" + ChainBreakState.effectiveShape()
                        + "，上限=" + ChainBreakConfig.maxBlocks
                        + "，触发=" + ChainBreakConfig.trigger);
    }
}
