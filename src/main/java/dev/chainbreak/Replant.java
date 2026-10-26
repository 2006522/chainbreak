package dev.chainbreak;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;

/**
 * 补种用的小工具：从原木反查它的树苗。
 *
 * <p>Minecraft 没有「原木 → 树苗」的注册表映射，但 id 命名是稳定的：
 * {@code oak_log} / {@code oak_wood} / {@code stripped_oak_log} 都指向 {@code oak_sapling}，
 * {@code spruce_log} → {@code spruce_sapling}，{@code mangrove_log} → {@code mangrove_sapling}。
 * 所以按名字推导，最后再用 {@code minecraft:saplings} 标签验一次，推导不出来（比如
 * {@code crimson_stem} 对应的是菌不是树苗）就不补种，绝不乱种。</p>
 */
public final class Replant {

    /** 原木类方块 id 的后缀，去掉后剩下的就是树种名。 */
    private static final String[] WOOD_SUFFIXES = {"_log", "_wood", "_stem", "_hyphae"};

    private static final String STRIPPED = "stripped_";

    private Replant() {
    }

    /**
     * 这个原木对应的树苗方块；推导不出来返回 {@code null}。
     */
    public static Block saplingFor(Block log) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(log);
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        if (path.startsWith(STRIPPED)) {
            // 去皮原木也长同一种树
            path = path.substring(STRIPPED.length());
        }
        String wood = null;
        for (String suffix : WOOD_SUFFIXES) {
            if (path.endsWith(suffix) && path.length() > suffix.length()) {
                wood = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        if (wood == null) {
            return null;
        }
        Identifier saplingId = Identifier.tryParse(id.getNamespace() + ":" + wood + "_sapling");
        if (saplingId == null) {
            return null;
        }
        Block sapling = BuiltInRegistries.BLOCK.getValue(saplingId);
        if (sapling == null) {
            return null;
        }
        // 必须真的是树苗：防止某些 id 恰好撞上普通方块被种出去
        return sapling.defaultBlockState().is(BlockTags.SAPLINGS, state -> true) ? sapling : null;
    }
}
