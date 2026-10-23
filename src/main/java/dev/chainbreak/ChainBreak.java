package dev.chainbreak;

/**
 * 连锁破坏 ChainBreak —— 常量与日志。
 * 不依赖 slf4j，使用 JDK 的 System.Logger，保持轻量。
 */
public final class ChainBreak {

    public static final String MOD_ID = "chainbreak";
    public static final String VERSION = "4.0.0";
    public static final System.Logger LOGGER = System.getLogger("chainbreak");

    private ChainBreak() {
    }
}
