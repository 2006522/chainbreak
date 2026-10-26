package dev.chainbreak;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 配置读取/保存，读取游戏目录下 config/chainbreak.properties。
 * 缺失时自动生成默认文件；命令改动后会回写该文件。
 */
public final class ChainBreakConfig {

    public static final File FILE = new File(new File("config"), "chainbreak.properties");

    /** 形状：shapeless / tunnel_1x1 / tunnel_3x3 / square_3x3 / stair_up / stair_down */
    public static String shape = "shapeless";
    /**
     * 触发方式：key = 按住连锁键（默认 ` 键，单人有效）；
     * sneak = 潜行挖掘即可；both = 两者任一。
     */
    public static String trigger = "key";
    /** 单次连锁的最大方块数。 */
    public static int maxBlocks = 128;
    /** 主手必须是"可损耗的工具"（或 included_tools 里的物品）。默认 false，与 FTB 一致。 */
    public static boolean requireTool = false;
    /** 工具必须能有效采集目标方块（能掉落物品）。默认 false，空手/普通物品也能连锁。 */
    public static boolean requireValidToolForBlock = false;
    /** 形状模式下，是否允许破坏体积内的任意方块。 */
    public static boolean shapedAnyBlock = true;
    /** 无形状模式下，是否允许破坏不同类型的相邻方块。 */
    public static boolean shapelessAnyBlock = false;
    /** 每破坏一个方块增加的饥饿消耗。 */
    public static double exhaustionPerBlock = 0.005D;
    /** 每破坏一个方块消耗的经验点（0 = 不消耗）。 */
    public static double experiencePerBlock = 0.0D;
    /** 连锁冷却（刻，20 刻 = 1 秒）。 */
    public static int cooldownTicks = 0;
    /** 工具剩余耐久 <= 此值时停止连锁；0 表示允许把工具用坏。 */
    public static int preventToolBreak = 1;
    /** 遇到无法破坏的方块时停止整轮连锁。 */
    public static boolean cancelOnBlockBreakFail = false;
    /** 连锁结束后在快捷栏上方显示提示。 */
    public static boolean feedback = true;
    /** 掉落方式：normal=原地掉落 / inventory=直接进背包 / feet=掉到玩家脚下 */
    public static String dropMode = "normal";
    /**
     * 作物按成熟度过滤：起始方块是带 {@code age} 属性的作物时，
     * 只连锁<b>同种作物且成熟阶段相同</b>的方块（挖成熟小麦不会铲掉没熟的）。
     */
    public static boolean cropSameAge = true;
    /** 连锁挖完自动补种：作物补种子、树补树苗（背包里有才补；创造模式不扣）。 */
    public static boolean replant = false;
    /** 连锁键 + 右键种子：一次种一片（3×3 水平）。 */
    public static boolean chainPlant = false;
    /** 挖树时连带把连通的树叶一起挖掉（只对原木生效）。 */
    public static boolean fellLeaves = false;
    /** 永不连锁的方块 id。 */
    public static Set<String> blacklist = new LinkedHashSet<>(Arrays.asList("minecraft:bedrock", "minecraft:barrier"));
    /** 额外允许当作"工具"的物品 id。 */
    public static Set<String> includedTools = new LinkedHashSet<>();
    /** 背包界面里那个 ⚙ 设置的屏幕坐标（可拖动改变）。 */
    public static int buttonX = 4;
    public static int buttonY = 4;
    /** 禁止触发连锁的物品 id。 */
    public static Set<String> excludedTools = new LinkedHashSet<>();

    private static boolean loaded = false;

    /**
     * 黑名单 / 工具名单解析成注册表对象后的缓存。
     * 连锁挖矿的 BFS 每个方块都要判一次黑名单，用对象比较可以避免每次
     * {@code Identifier.toString()} 的字符串分配。
     */
    private static Set<Block> blacklistBlocks = Set.of();
    private static Set<Item> includedToolItems = Set.of();
    private static Set<Item> excludedToolItems = Set.of();

    private ChainBreakConfig() {
    }

    /** 方块是否在黑名单里（优先走对象集合，未解析到的 id 才回退字符串比较）。 */
    public static boolean isBlacklisted(Block block) {
        if (blacklistBlocks.contains(block)) {
            return true;
        }
        return blacklistBlocks.size() < blacklist.size()
                && blacklist.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
    }

    /** 物品 id 是否在"额外算作工具"名单里。 */
    public static boolean isIncludedTool(Item item) {
        if (includedToolItems.contains(item)) {
            return true;
        }
        return includedToolItems.size() < includedTools.size()
                && includedTools.contains(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** 物品 id 是否在"禁止触发"名单里。 */
    public static boolean isExcludedTool(Item item) {
        if (excludedToolItems.contains(item)) {
            return true;
        }
        return excludedToolItems.size() < excludedTools.size()
                && excludedTools.contains(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** 把字符串名单解析成注册表对象（配置变动后调用）。 */
    private static void rebuildIndexes() {
        blacklistBlocks = resolveBlocks(blacklist);
        includedToolItems = resolveItems(includedTools);
        excludedToolItems = resolveItems(excludedTools);
    }

    private static Set<Block> resolveBlocks(Set<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<Block> out = new HashSet<>(ids.size() * 2);
        for (String id : ids) {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) {
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(rl);
            if (block != null) {
                out.add(block);
            }
        }
        return out;
    }

    private static Set<Item> resolveItems(Set<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<Item> out = new HashSet<>(ids.size() * 2);
        for (String id : ids) {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(rl);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** 把所有选项恢复成出厂默认（界面上的「恢复默认」按钮用）。 */
    public static void restoreDefaults() {
        shape = "shapeless";
        trigger = "key";
        maxBlocks = 128;
        requireTool = false;
        requireValidToolForBlock = false;
        shapedAnyBlock = true;
        shapelessAnyBlock = false;
        exhaustionPerBlock = 0.005D;
        experiencePerBlock = 0.0D;
        cooldownTicks = 0;
        preventToolBreak = 1;
        cancelOnBlockBreakFail = false;
        feedback = true;
        dropMode = "normal";
        cropSameAge = true;
        replant = false;
        chainPlant = false;
        fellLeaves = false;
        blacklist = new LinkedHashSet<>(Arrays.asList("minecraft:bedrock", "minecraft:barrier"));
        includedTools = new LinkedHashSet<>();
        excludedTools = new LinkedHashSet<>();
        buttonX = 4;
        buttonY = 4;
        rebuildIndexes();
    }

    public static void loadIfNeeded() {
        if (loaded) {
            return;
        }
        reload();
    }

    /** 重新从磁盘加载（命令 /chainbreak reload 也走这里）。 */
    public static void reload() {
        loaded = true;
        Properties p = new Properties();
        if (FILE.isFile()) {
            try (FileInputStream in = new FileInputStream(FILE)) {
                p.load(in);
            } catch (Exception e) {
                ChainBreak.LOGGER.log(System.Logger.Level.WARNING, "[chainbreak] 读取配置失败，使用默认值", e);
            }
        }

        shape = str(p, "shape", shape).trim().toLowerCase();
        trigger = str(p, "trigger", trigger).trim().toLowerCase();
        maxBlocks = clamp(i(p, "max_blocks", maxBlocks), 1, 1024);
        requireTool = b(p, "require_tool", requireTool);
        requireValidToolForBlock = b(p, "require_valid_tool_for_block", requireValidToolForBlock);
        shapedAnyBlock = b(p, "shaped_any_block", shapedAnyBlock);
        shapelessAnyBlock = b(p, "shapeless_any_block", shapelessAnyBlock);
        exhaustionPerBlock = Math.max(0.0D, d(p, "exhaustion_per_block", exhaustionPerBlock));
        experiencePerBlock = Math.max(0.0D, d(p, "experience_per_block", experiencePerBlock));
        cooldownTicks = clamp(i(p, "ultimine_cooldown", cooldownTicks), 0, 72000);
        preventToolBreak = clamp(i(p, "prevent_tool_break", preventToolBreak), 0, 10000);
        cancelOnBlockBreakFail = b(p, "cancel_on_block_break_fail", cancelOnBlockBreakFail);
        feedback = b(p, "feedback", feedback);
        dropMode = str(p, "drop_mode", dropMode).trim().toLowerCase();
        cropSameAge = b(p, "crop_same_age", cropSameAge);
        replant = b(p, "replant", replant);
        chainPlant = b(p, "chain_plant", chainPlant);
        fellLeaves = b(p, "fell_leaves", fellLeaves);
        buttonX = i(p, "button_x", buttonX);
        buttonY = i(p, "button_y", buttonY);
        blacklist = set(p, "blacklist", blacklist);
        includedTools = set(p, "included_tools", includedTools);
        excludedTools = set(p, "excluded_tools", excludedTools);
        rebuildIndexes();

        if (!FILE.isFile()) {
            save();
        }
    }

    /** 把当前内存中的配置写回文件。 */
    public static void save() {
        rebuildIndexes();
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            Properties out = new Properties();
            out.setProperty("shape", shape);
            out.setProperty("trigger", trigger);
            out.setProperty("max_blocks", Integer.toString(maxBlocks));
            out.setProperty("require_tool", Boolean.toString(requireTool));
            out.setProperty("require_valid_tool_for_block", Boolean.toString(requireValidToolForBlock));
            out.setProperty("shaped_any_block", Boolean.toString(shapedAnyBlock));
            out.setProperty("shapeless_any_block", Boolean.toString(shapelessAnyBlock));
            out.setProperty("exhaustion_per_block", Double.toString(exhaustionPerBlock));
            out.setProperty("experience_per_block", Double.toString(experiencePerBlock));
            out.setProperty("ultimine_cooldown", Integer.toString(cooldownTicks));
            out.setProperty("prevent_tool_break", Integer.toString(preventToolBreak));
            out.setProperty("cancel_on_block_break_fail", Boolean.toString(cancelOnBlockBreakFail));
            out.setProperty("feedback", Boolean.toString(feedback));
            out.setProperty("drop_mode", dropMode);
            out.setProperty("crop_same_age", Boolean.toString(cropSameAge));
            out.setProperty("replant", Boolean.toString(replant));
            out.setProperty("chain_plant", Boolean.toString(chainPlant));
            out.setProperty("fell_leaves", Boolean.toString(fellLeaves));
            out.setProperty("button_x", Integer.toString(buttonX));
            out.setProperty("button_y", Integer.toString(buttonY));
            out.setProperty("blacklist", String.join(",", blacklist));
            out.setProperty("included_tools", String.join(",", includedTools));
            out.setProperty("excluded_tools", String.join(",", excludedTools));
            try (FileOutputStream fos = new FileOutputStream(FILE)) {
                out.store(fos, "ChainBreak - 连锁挖矿配置（对齐 FTB Ultimine 服务端设置）");
            }
        } catch (Exception e) {
            ChainBreak.LOGGER.log(System.Logger.Level.WARNING, "[chainbreak] 写入配置失败", e);
        }
    }

    private static String str(Properties p, String k, String def) {
        String v = p.getProperty(k);
        return v == null ? def : v;
    }

    private static int i(Properties p, String k, int def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double d(Properties p, String k, double def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean b(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static Set<String> set(Properties p, String k, Set<String> def) {
        String v = p.getProperty(k);
        if (v == null) {
            return def;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : v.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
