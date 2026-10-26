package dev.chainbreak.client;

import dev.chainbreak.ChainBreakState;
import dev.chainbreak.ChainSearch;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 连锁预览的候选方块计算（客户端，HUD 预估与线框渲染共用）。
 * <p>
 * 候选搜索与服务端共用 {@link ChainSearch}，所以"预览几个就挖几个"；
 * 这里只额外负责读懂准星指向、缓存结果，避免每帧重复 BFS。
 */
public final class ChainBreakPreview {

    private static final long CACHE_NANOS = 50_000_000L; // 50ms

    /** 连锁部分（<b>不含</b>起始方块）。null 表示准星那格不合格，不做连锁。 */
    private static List<BlockPos> cached;
    /** 线框要画的方块：起始方块 + 连锁部分。 */
    private static List<BlockPos> cachedOutlines;
    private static long cacheAt;

    private ChainBreakPreview() {
    }

    /**
     * 准星指向方块按当前形状将被连锁挖掉的方块列表（<b>不含</b>起始方块）。
     * 起始方块不合格（未指向/空气/黑名单/工具不符）时返回 null。
     */
    public static List<BlockPos> candidates(Minecraft mc) {
        ensure(mc);
        return cached;
    }

    /**
     * 按住连锁键时要画线框的方块：<b>起始方块也在里面</b>。
     *
     * <p>起始方块必须画。HUD 的「采掘 N 方块」和真正破坏的数量都把它算进去了，
     * 线框却只画连锁部分的话，准星那一格看起来就像"没被选中"——尤其原版自己的
     * 方块轮廓是很细的暗色框，站在暗处几乎看不见，对比之下更明显。</p>
     */
    public static List<BlockPos> outlineTargets(Minecraft mc) {
        ensure(mc);
        return cachedOutlines;
    }

    private static void ensure(Minecraft mc) {
        long now = System.nanoTime();
        // 用 cacheAt 判断"算过了"而不是 cached != null：准星没指向方块时 cached 也是 null，
        // 那种情况下也应当命中缓存，不然每帧都要重算一遍
        if (cacheAt != 0L && now - cacheAt < CACHE_NANOS) {
            return;
        }
        cacheAt = now;
        cached = compute(mc);
        cachedOutlines = cached == null ? null : buildOutlines(mc, cached);
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

    private static List<BlockPos> buildOutlines(Minecraft mc, List<BlockPos> chain) {
        List<BlockPos> out = new ArrayList<>(chain.size() + 1);
        if (mc.hitResult instanceof BlockHitResult bhr) {
            out.add(bhr.getBlockPos().immutable());
        }
        out.addAll(chain);
        return out;
    }
}
