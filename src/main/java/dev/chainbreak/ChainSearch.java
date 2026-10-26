package dev.chainbreak;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选方块搜索 —— 服务端（真正破坏）与客户端（线框预览 / HUD 预估）共用同一份实现，
 * 保证"预览几个就挖几个"。
 * <p>
 * 过滤规则集中在 {@link #isCandidate}：已加载、非空气、硬度 &gt;= 0、不在黑名单、
 * 需要正确工具时工具必须匹配。
 * <p>
 * 另外还有一条"作物成熟度"规则（见 {@link #ageProperty}）：起始方块如果是带
 * {@code age} 属性的作物，候选必须<b>同种作物且成熟阶段相同</b> ——
 * 挖成熟的小麦不会顺手把旁边没熟的一起铲掉。
 */
public final class ChainSearch {

    private ChainSearch() {
    }

    /**
     * 取方块状态里的 {@code age} 属性（作物成熟度），没有则返回 null。
     *
     * <p>不写死 {@code CropBlock}：小麦/胡萝卜/马铃薯/甜菜走 CropBlock，
     * 但下界疣、可可、甜浆果、甘蔗、海带各有各的类，共同的只有根上的
     * {@code age} 整型属性。用属性而不是类，规则对所有作物一致。</p>
     */
    public static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && "age".equals(integer.getName())) {
                return integer;
            }
        }
        return null;
    }

    /** 这个方块是不是"有成熟度概念的作物"。 */
    public static boolean isCrop(BlockState state) {
        return ageProperty(state) != null;
    }

    /**
     * 按形状收集候选方块（<b>不含</b>起始方块，它由调用方处理）。
     *
     * @param face 水平朝向，通道 / 楼梯沿此方向延伸
     */
    public static List<BlockPos> collect(Level level, String shape, BlockPos origin,
                                         BlockState originState, Direction face, ItemStack tool) {
        // 起始方块自己占 1 个名额，候选（即连锁部分）最多取 maxBlocks - 1 个，
        // 这样线框预览、HUD 预估和真正破坏的数量三者一致
        int limit = Math.max(0, ChainBreakConfig.maxBlocks - 1);
        if (Shapes.SHAPELESS.equals(shape)) {
            return collectVein(level, origin, originState, tool, limit);
        }

        // 几何形状：先按形状枚举，再统一过滤（与无形状模式同一套规则）
        List<BlockPos> out = new ArrayList<>(Math.min(limit, 64));
        boolean anyBlock = ChainBreakConfig.shapedAnyBlock;
        IntegerProperty age = ChainBreakConfig.cropSameAge ? ageProperty(originState) : null;
        Integer originAge = age == null ? null : originState.getValue(age);

        for (BlockPos pos : Shapes.geometric(shape, origin, face, limit)) {
            if (!isCandidate(level, pos, tool)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (age != null) {
                // 作物：只认同种且同一成熟阶段
                if (state.getBlock() != originState.getBlock() || !originAge.equals(state.getValue(age))) {
                    continue;
                }
            } else if (!anyBlock && state.getBlock() != originState.getBlock()) {
                continue;
            }
            out.add(pos);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** 无形状模式：以起始方块为中心做 BFS，只看相邻方块。 */
    public static List<BlockPos> collectVein(Level level, BlockPos origin, BlockState originState,
                                             ItemStack tool, int limit) {
        boolean anyBlock = ChainBreakConfig.shapelessAnyBlock;
        int budget = limit * 8 + 64;
        IntegerProperty age = ChainBreakConfig.cropSameAge ? ageProperty(originState) : null;
        Integer originAge = age == null ? null : originState.getValue(age);

        List<BlockPos> out = new ArrayList<>(Math.min(limit, 64));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        BlockPos start = origin.immutable();
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
                if (!isCandidate(level, next, tool)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (age != null) {
                    if (state.getBlock() != originState.getBlock() || !originAge.equals(state.getValue(age))) {
                        continue;
                    }
                } else if (!anyBlock && state.getBlock() != originState.getBlock()) {
                    continue;
                }
                out.add(next);
                queue.add(next);
            }
        }
        return out;
    }

    /** 某个坐标是否允许被连锁挖矿。 */
    public static boolean isCandidate(Level level, BlockPos pos, ItemStack tool) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // 硬度 < 0 表示不可破坏（基岩、屏障等）
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        if (ChainBreakConfig.isBlacklisted(state.getBlock())) {
            return false;
        }
        return !(ChainBreakConfig.requireValidToolForBlock
                && !tool.isEmpty()
                && state.requiresCorrectToolForDrops()
                && !tool.isCorrectToolForDrops(state));
    }
}
