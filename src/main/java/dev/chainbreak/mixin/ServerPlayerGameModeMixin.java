package dev.chainbreak.mixin;

import dev.chainbreak.ChainBreak;
import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreakState;
import dev.chainbreak.Shapes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连锁破坏核心，挂在服务端 ServerPlayerGameMode#destroyBlock 上。
 * <p>
 * 玩家正常挖掉一个方块后，若满足条件，按配置的形状收集候选方块，逐个走原版
 * {@code destroyBlock} 流程销毁 —— 掉落物、时运/精准采集、工具耐久、统计、
 * 冒险模式限制、领地保护判定全部沿用原版逻辑。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    /** 防重入：连锁过程中产生的 destroyBlock 调用不再触发连锁。 */
    @Unique
    private static final ThreadLocal<Boolean> CHAINBREAK_BUSY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 每名玩家的上次连锁时间（毫秒），用于冷却。 */
    @Unique
    private static final Map<UUID, Long> CHAINBREAK_LAST_USE = new ConcurrentHashMap<>();

    /** HEAD 阶段记录被挖方块的原状态（RETURN 时它已经变成空气）。 */
    @Unique
    private BlockState chainbreak$originState;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void chainbreak$captureOrigin(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.level.getBlockState(pos);
        this.chainbreak$originState = state;

        // 非 normal 掉落模式：起始方块也走自定义掉落，取消原版破坏流程
        if (!CHAINBREAK_BUSY.get() && state != null && !state.isAir()
                && !"normal".equals(ChainBreakConfig.dropMode)) {
            ItemStack tool = this.player.getMainHandItem();
            if (this.chainbreak$isTriggered()
                    && !this.chainbreak$blocked(tool, state)) {
                // 冷却检查
                long now = System.currentTimeMillis();
                long cooldownMs = ChainBreakConfig.cooldownTicks * 50L;
                Long last = CHAINBREAK_LAST_USE.get(this.player.getUUID());
                if (!(cooldownMs > 0 && last != null && now - last < cooldownMs)) {
                    // 接管：破坏起始方块 + 连锁，统一掉落
                    this.chainbreak$originState = null;
                    this.chainbreak$runChainWithOrigin(pos, state);
                    cir.setReturnValue(Boolean.TRUE);
                    return;
                }
            }
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void chainbreak$onDestroyed(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState origin = this.chainbreak$originState;
        this.chainbreak$originState = null;

        if (origin == null || !cir.getReturnValueZ() || CHAINBREAK_BUSY.get() || origin.isAir()) {
            return;
        }
        try {
            this.chainbreak$runChain(pos, origin);
        } catch (Exception e) {
            ChainBreak.LOGGER.log(System.Logger.Level.ERROR, "[chainbreak] 连锁破坏时发生异常", e);
        }
    }

    /** 门槛检查（不触发时不接管，走原版）。 */
    @Unique
    private boolean chainbreak$blocked(ItemStack tool, BlockState origin) {
        String toolId = BuiltInRegistries.ITEM.getKey(tool.getItem()).toString();
        if (!ChainBreakConfig.excludedTools.isEmpty() && ChainBreakConfig.excludedTools.contains(toolId)) {
            return true;
        }
        if (ChainBreakConfig.requireTool
                && !tool.isDamageableItem()
                && !ChainBreakConfig.includedTools.contains(toolId)) {
            return true;
        }
        if (ChainBreakConfig.blacklist.contains(
                BuiltInRegistries.BLOCK.getKey(origin.getBlock()).toString())) {
            return true;
        }
        if (ChainBreakConfig.requireValidToolForBlock
                && !tool.isEmpty()
                && origin.requiresCorrectToolForDrops()
                && !tool.isCorrectToolForDrops(origin)) {
            return true;
        }
        return false;
    }

    @Unique
    private void chainbreak$runChain(BlockPos pos, BlockState origin) {
        ChainBreakConfig.loadIfNeeded();

        ItemStack tool = this.player.getMainHandItem();

        // 1. 触发条件
        if (this.chainbreak$blocked(tool, origin)) {
            return;
        }
        if (!this.chainbreak$isTriggered()) {
            return;
        }

        // 2. 冷却
        long now = System.currentTimeMillis();
        long cooldownMs = ChainBreakConfig.cooldownTicks * 50L;
        Long last = CHAINBREAK_LAST_USE.get(this.player.getUUID());
        if (cooldownMs > 0 && last != null && now - last < cooldownMs) {
            this.chainbreak$feedback("§c连锁破坏冷却中");
            return;
        }

        // 3. 经验预检（至少要够扣一个方块）
        double xpPerBlock = ChainBreakConfig.experiencePerBlock;
        if (xpPerBlock > 0.0D && this.player.totalExperience < Math.round(xpPerBlock)) {
            this.chainbreak$feedback("§c经验不足");
            return;
        }

        // 4. 收集候选方块（形状可由按键或命令实时切换）
        String shape = ChainBreakState.effectiveShape();
        List<BlockPos> candidates;
        if (Shapes.SHAPELESS.equals(shape)) {
            candidates = this.chainbreak$collectVein(pos, origin, tool);
        } else {
            Direction face = this.player.getDirection();
            candidates = Shapes.geometric(shape, pos, face, ChainBreakConfig.maxBlocks);
        }
        if (candidates.isEmpty()) {
            return;
        }

        // 5. 逐个破坏
        int broken = 0;
        CHAINBREAK_BUSY.set(Boolean.TRUE);
        try {
            for (BlockPos target : candidates) {
                if (broken >= ChainBreakConfig.maxBlocks) {
                    break;
                }
                if (this.chainbreak$toolSpent(tool)) {
                    break;
                }
                if (!this.chainbreak$isCandidate(target, origin, tool)) {
                    continue;
                }
                boolean ok = this.chainbreak$destroy(target, tool);
                if (ok) {
                    broken++;
                } else if (ChainBreakConfig.cancelOnBlockBreakFail) {
                    break;
                }
            }
        } finally {
            CHAINBREAK_BUSY.set(Boolean.FALSE);
        }

        if (broken <= 0) {
            return;
        }

        // 6. 结算消耗与冷却
        if (ChainBreakConfig.cooldownTicks > 0) {
            CHAINBREAK_LAST_USE.put(this.player.getUUID(), now);
        }
        if (ChainBreakConfig.exhaustionPerBlock > 0.0D) {
            this.player.causeFoodExhaustion((float) (ChainBreakConfig.exhaustionPerBlock * broken));
        }
        if (xpPerBlock > 0.0D) {
            int cost = (int) Math.min(Math.round(xpPerBlock * broken), this.player.totalExperience);
            if (cost > 0) {
                this.player.giveExperiencePoints(-cost);
            }
        }
        if (ChainBreakConfig.feedback) {
            this.chainbreak$feedback("§a" + origin.getBlock().getName().getString()
                    + " §f×" + (broken + 1));
        }
        ChainBreakState.lastBroken = broken + 1;
    }

    /** 触发条件：按键 / 潜行 / 两者任一，由配置 trigger 决定。 */
    @Unique
    private boolean chainbreak$isTriggered() {
        boolean sneaking = this.player.isShiftKeyDown();
        boolean key = ChainBreakState.keyDown;
        return switch (ChainBreakConfig.trigger) {
            case "sneak" -> sneaking;
            case "both" -> sneaking || key;
            default -> key;
        };
    }

    /** 无形状模式：以起始方块为中心做 BFS，只连同样式的相邻方块。 */
    @Unique
    private List<BlockPos> chainbreak$collectVein(BlockPos pos, BlockState origin, ItemStack tool) {
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
                if (!this.chainbreak$isCandidate(next, origin, tool)) {
                    continue;
                }
                if (!anyBlock && this.level.getBlockState(next).getBlock() != origin.getBlock()) {
                    continue;
                }
                out.add(next);
                queue.add(next);
            }
        }
        return out;
    }

    /** 判断某个坐标是否允许被连锁破坏。 */
    @Unique
    private boolean chainbreak$isCandidate(BlockPos pos, BlockState origin, ItemStack tool) {
        if (!this.level.isLoaded(pos)) {
            return false;
        }
        BlockState state = this.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // 硬度 < 0 表示不可破坏（基岩、屏障等）
        if (state.getDestroySpeed(this.level, pos) < 0.0F) {
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

    /** 工具即将损坏时停止连锁（空手不算耗尽，返回 false 继续连锁）。 */
    @Unique
    private boolean chainbreak$toolSpent(ItemStack tool) {
        if (tool.isEmpty()) {
            return false;
        }
        int guard = ChainBreakConfig.preventToolBreak;
        if (guard <= 0 || !tool.isDamageableItem()) {
            return false;
        }
        int remaining = tool.getMaxDamage() - tool.getDamageValue();
        return remaining <= guard;
    }

    @Unique
    private void chainbreak$feedback(String text) {
        if (!ChainBreakConfig.feedback) {
            return;
        }
        try {
            this.player.sendOverlayMessage(Component.literal(text));
        } catch (Exception e) {
            ChainBreak.LOGGER.log(System.Logger.Level.DEBUG, "[chainbreak] 提示发送失败", e);
        }
    }

    /**
     * 非 normal 掉落模式下接管破坏：起始方块 + 连锁方块统一按 drop_mode 掉落。
     */
    @Unique
    private void chainbreak$runChainWithOrigin(BlockPos pos, BlockState origin) {
        ChainBreakConfig.loadIfNeeded();
        ItemStack tool = this.player.getMainHandItem();

        CHAINBREAK_BUSY.set(Boolean.TRUE);
        try {
            // 先破坏起始方块（自定义掉落）
            this.chainbreak$destroy(pos, tool);

            // 收集候选
            String shape = ChainBreakState.effectiveShape();
            List<BlockPos> candidates;
            if (Shapes.SHAPELESS.equals(shape)) {
                candidates = this.chainbreak$collectVein(pos, origin, tool);
            } else {
                Direction face = this.player.getDirection();
                candidates = Shapes.geometric(shape, pos, face, ChainBreakConfig.maxBlocks);
            }

            int broken = 0;
            for (BlockPos target : candidates) {
                if (broken >= ChainBreakConfig.maxBlocks) {
                    break;
                }
                if (this.chainbreak$toolSpent(tool)) {
                    break;
                }
                if (!this.chainbreak$isCandidate(target, origin, tool)) {
                    continue;
                }
                if (this.chainbreak$destroy(target, tool)) {
                    broken++;
                } else if (ChainBreakConfig.cancelOnBlockBreakFail) {
                    break;
                }
            }

            // 结算（broken 不含起始方块，+1 计总数）
            if (ChainBreakConfig.cooldownTicks > 0) {
                CHAINBREAK_LAST_USE.put(this.player.getUUID(), System.currentTimeMillis());
            }
            if (ChainBreakConfig.exhaustionPerBlock > 0.0D) {
                this.player.causeFoodExhaustion((float) (ChainBreakConfig.exhaustionPerBlock * (broken + 1)));
            }
            if (ChainBreakConfig.experiencePerBlock > 0.0D) {
                int cost = (int) Math.min(Math.round(ChainBreakConfig.experiencePerBlock * (broken + 1)),
                        this.player.totalExperience);
                if (cost > 0) {
                    this.player.giveExperiencePoints(-cost);
                }
            }
            if (ChainBreakConfig.feedback) {
                this.chainbreak$feedback("§a" + origin.getBlock().getName().getString()
                        + " §f×" + (broken + 1));
            }
            ChainBreakState.lastBroken = broken + 1;
        } finally {
            CHAINBREAK_BUSY.set(Boolean.FALSE);
        }
    }

    /**
     * 破坏一个方块并按 drop_mode 处理掉落。
     * normal 走原版 destroyBlock（原地掉落）；inventory 进背包；feet 掉到玩家脚下。
     */
    @Unique
    private boolean chainbreak$destroy(BlockPos pos, ItemStack tool) {
        String mode = ChainBreakConfig.dropMode;
        if ("normal".equals(mode)) {
            return this.player.gameMode.destroyBlock(pos);
        }

        BlockState state = this.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        // 用原版 getDrops 计算掉落（传入玩家与工具以正确处理精准采集/时运）
        List<ItemStack> drops = Block.getDrops(state, this.level, pos,
                this.level.getBlockEntity(pos), this.player, tool);

        // 移除方块（不生成实体掉落）
        this.level.removeBlock(pos, false);

        if ("inventory".equals(mode)) {
            for (ItemStack drop : drops) {
                if (!this.player.getInventory().add(drop.copy())) {
                    // 背包满则掉到脚下兜底
                    Containers.dropItemStack(this.level, this.player.getX(),
                            this.player.getY(), this.player.getZ(), drop);
                }
            }
        } else { // feet
            for (ItemStack drop : drops) {
                Containers.dropItemStack(this.level, this.player.getX(),
                        this.player.getY(), this.player.getZ(), drop);
            }
        }
        return true;
    }
}
