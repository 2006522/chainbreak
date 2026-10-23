package dev.chainbreak.client;

import dev.chainbreak.ChainBreakState;
import dev.chainbreak.ChainSearch;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * 连锁预览的候选方块计算（客户端，HUD 预估与线框渲染共用）。
 * <p>
 * 候选搜索与服务端共用 {@link ChainSearch}，所以"预览几个就挖几个"；
 * 这里只额外负责读懂准星指向、缓存结果，避免每帧重复 BFS。
 */
public final class ChainBreakPreview {

    private static final long CACHE_NANOS = 50_000_000L; // 50ms

    private static List<BlockPos> cached;
    private static long cacheAt;

    private ChainBreakPreview() {
    }

    /**
     * 准星指向方块按当前形状将被连锁挖掉的方块列表（<b>不含</b>起始方块）。
     * 起始方块不合格（未指向/空气/黑名单/工具不符）时返回 null。
     */
    public static List<BlockPos> candidates(Minecraft mc) {
        long now = System.nanoTime();
        if (cached != null && now - cacheAt < CACHE_NANOS) {
            return cached;
        }
        cacheAt = now;
        cached = compute(mc);
        return cached;
    }

    private static List<BlockPos> compute(Minecraft mc) {
        if (mc.level == null || mc.player == null
                || !(mc.hitResult instanceof BlockHitResult bhr)
                || bhr.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos origin = bhr.getBlockPos();
        BlockState originState = mc.level.getBlockState(origin);
        ItemStack tool = mc.player.getMainHandItem();

        // 起始方块本身不合格就不显示预览
        if (!ChainSearch.isCandidate(mc.level, origin, tool)) {
            return null;
        }
        return ChainSearch.collect(mc.level, ChainBreakState.effectiveShape(), origin,
                originState, mc.player.getDirection(), tool);
    }
}
