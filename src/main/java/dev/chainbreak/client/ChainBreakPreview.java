package dev.chainbreak.client;

import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreakState;
import dev.chainbreak.Shapes;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 连锁预览的候选方块计算（客户端，HUD 预估与线框渲染共用）。
 * 逻辑与服务端一致：黑名单、硬度、同块规则、工具匹配、上限。
 */
public final class ChainBreakPreview {

    private static List<BlockPos> cached;
    private static long cacheAt;

    private ChainBreakPreview() {
    }

    /**
     * 准星指向方块按当前形状将被连锁挖掉的方块列表（不含起始方块）。
     * 起始方块不合格（未指向/空气/黑名单/工具不符）时返回 null。
     */
    public static List<BlockPos> candidates(Minecraft mc) {
        // 50ms 缓存，避免每帧重复 BFS
        long now = System.nanoTime();
        if (cached != null && now - cacheAt < 50_000_000L) {
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

        if (originState.isAir()
                || originState.getDestroySpeed(mc.level, origin) < 0.0F
                || ChainBreakConfig.blacklist.contains(
                        BuiltInRegistries.BLOCK.getKey(originState.getBlock()).toString())) {
            return null;
        }
        if (ChainBreakConfig.requireValidToolForBlock
                && !tool.isEmpty()
                && originState.requiresCorrectToolForDrops()
                && !tool.isCorrectToolForDrops(originState)) {
            return null;
        }

        String shape = ChainBreakState.effectiveShape();
        boolean shapeless = Shapes.SHAPELESS.equals(shape);
        boolean anyBlock = shapeless ? ChainBreakConfig.shapelessAnyBlock : ChainBreakConfig.shapedAnyBlock;

        // 未过滤的形状候选
        List<BlockPos> raw;
        if (shapeless) {
            raw = collectVeinClient(mc, origin, originState);
        } else {
            raw = Shapes.geometric(shape, origin, mc.player.getDirection(),
                    ChainBreakConfig.maxBlocks);
        }

        // 统一过滤
        List<BlockPos> out = new ArrayList<>(raw.size());
        for (BlockPos p : raw) {
            BlockState s = mc.level.getBlockState(p);
            if (!candidateOk(mc, s, p, tool)) {
                continue;
            }
            if (!anyBlock && s.getBlock() != originState.getBlock()) {
                continue;
            }
            out.add(p);
            if (out.size() >= ChainBreakConfig.maxBlocks) {
                break;
            }
        }
        return out;
    }

    /** 客户端版矿脉 BFS（与服务端 collectVein 一致）。 */
    private static List<BlockPos> collectVeinClient(Minecraft mc, BlockPos pos, BlockState origin) {
        int limit = ChainBreakConfig.maxBlocks;
        int budget = limit * 8 + 64;
        boolean anyBlock = ChainBreakConfig.shapelessAnyBlock;

        List<BlockPos> out = new ArrayList<>(Math.min(limit, 64));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        BlockPos start = pos.immutable();
        visited.add(start);
        queue.add(start);

        while (!queue.isEmpty() && out.size() < limit && budget-- > 0) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                if (out.size() >= limit) {
                    break;
                }
                BlockPos next = current.relative(dir);
                if (!visited.add(next)) {
                    continue;
                }
                BlockState s = mc.level.getBlockState(next);
                if (!candidateOk(mc, s, next, mc.player.getMainHandItem())) {
                    continue;
                }
                if (!anyBlock && s.getBlock() != origin.getBlock()) {
                    continue;
                }
                out.add(next);
                queue.add(next);
            }
        }
        return out;
    }

    /** 候选方块过滤（客户端版，与服务端 isCandidate 一致）。 */
    private static boolean candidateOk(Minecraft mc, BlockState state, BlockPos pos, ItemStack tool) {
        if (state.isAir()) {
            return false;
        }
        if (state.getDestroySpeed(mc.level, pos) < 0.0F) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (ChainBreakConfig.blacklist.contains(id)) {
            return false;
        }
        if (ChainBreakConfig.requireValidToolForBlock
                && !tool.isEmpty()
                && state.requiresCorrectToolForDrops()
                && !tool.isCorrectToolForDrops(state)) {
            return false;
        }
        return true;
    }
}
