package dev.chainbreak.mixin;

import dev.chainbreak.ChainBreak;
import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreakState;
import dev.chainbreak.ChainSearch;
import dev.chainbreak.Replant;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连锁挖矿核心，挂在服务端 ServerPlayerGameMode 上。
 * <p>
 * 玩家正常挖掉一个方块后，若满足条件，按配置的形状收集候选方块，逐个走原版
 * {@code destroyBlock} 流程销毁 —— 掉落物、时运/精准采集、工具耐久、统计、
 * 冒险模式限制、领地保护判定全部沿用原版逻辑。
 * <p>
 * drop_mode 不是 normal 时（进背包 / 掉到脚下），起始方块也要走自定义掉落，
 * 于是在 HEAD 阶段整轮接管，原版破坏不再执行。
 * <p>
 * 另外两件事也挂在这里：{@code destroyBlock} 之后按配置自动补种（replant），
 * 以及 {@code useItemOn} 成功种下作物后按配置连锁种一片（chain_plant）。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    /** 一键种植的范围：3×3 水平，中心优先（种子不够时先种中间那格）。 */
    @Unique
    private static final int[] CHAINBREAK_PLANT_OFFSETS = {
            0, 0,
            -1, 0, 1, 0, 0, -1, 0, 1,
            -1, -1, 1, -1, -1, 1, 1, 1
    };

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

    /** 本轮补种了几格（反馈用）。 */
    @Unique
    private int chainbreak$replanted;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void chainbreak$captureOrigin(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.level.getBlockState(pos);
        this.chainbreak$originState = state;

        // 只有非 normal 掉落模式才需要在 HEAD 接管（起始方块也要自定义掉落）
        if (CHAINBREAK_BUSY.get() || state == null || state.isAir()
                || "normal".equals(ChainBreakConfig.dropMode)) {
            return;
        }
        if (this.chainbreak$runChain(pos, state, true)) {
            // 接管成功：清掉记录，避免取消后在 RETURN 阶段再跑一遍连锁
            this.chainbreak$originState = null;
            cir.setReturnValue(Boolean.TRUE);
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
            this.chainbreak$runChain(pos, origin, false);
        } catch (Exception e) {
            ChainBreak.LOGGER.log(System.Logger.Level.ERROR, "[chainbreak] 连锁挖矿时发生异常", e);
        }
    }

    // ---------------------------------------------------------------- 一键连锁种植

    /**
     * 右键种子成功种下一格后，按 3×3 补种一片。
     *
     * <p>只对"作物方块"生效（方块自己带 {@code age} 属性），普通方块、火把、
     * 床之类照旧一次一个。触发条件和挖矿共用同一套（按键 / 潜行 / 两者）。</p>
     */
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void chainbreak$chainPlant(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (!ChainBreakConfig.chainPlant || level == null || level.isClientSide()) {
            return;
        }
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return; // 原版没种成功（没对准耕地、光照不够……）就别连锁
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        BlockState crop = blockItem.getBlock().defaultBlockState();
        if (ChainSearch.ageProperty(crop) == null) {
            return; // 不是作物
        }
        if (!this.chainbreak$isTriggered()) {
            return;
        }

        ChainBreakConfig.loadIfNeeded();

        // 原版把作物种在了哪一格：点击位置本身（可替换方块，如草）或点击面外侧（泥土上表面）
        BlockPos hitPos = hit.getBlockPos();
        BlockPos origin = level.getBlockState(hitPos).getBlock() == blockItem.getBlock()
                ? hitPos
                : hitPos.relative(hit.getDirection());

        int planted = 0;
        for (int i = 0; i < CHAINBREAK_PLANT_OFFSETS.length; i += 2) {
            if (stack.isEmpty()) {
                break;
            }
            BlockPos pos = origin.offset(CHAINBREAK_PLANT_OFFSETS[i], 0, CHAINBREAK_PLANT_OFFSETS[i + 1]);
            // 只往"可替换"里种：耕地、空气、草丛可以，水/石头/已有作物不行
            if (!level.getBlockState(pos).canBeReplaced()) {
                continue;
            }
            if (!crop.canSurvive(level, pos)) {
                continue;
            }
            level.setBlock(pos, crop, Block.UPDATE_ALL);
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            planted++;
        }

        if (planted > 0) {
            ChainBreakState.lastBroken = planted;
            if (ChainBreakConfig.feedback) {
                try {
                    player.sendOverlayMessage(Component.literal(
                            "§a" + crop.getBlock().getName().getString() + " §f+" + planted));
                } catch (Exception ignored) {
                    // 提示失败不影响功能
                }
            }
        }
    }

    /** 门槛检查（不满足时整轮不接管，走原版）。 */
    @Unique
    private boolean chainbreak$blocked(ItemStack tool, BlockState origin) {
        if (!tool.isEmpty() && ChainBreakConfig.isExcludedTool(tool.getItem())) {
            return true;
        }
        if (ChainBreakConfig.requireTool
                && !tool.isDamageableItem()
                && !ChainBreakConfig.isIncludedTool(tool.getItem())) {
            return true;
        }
        if (ChainBreakConfig.isBlacklisted(origin.getBlock())) {
            return true;
        }
        return ChainBreakConfig.requireValidToolForBlock
                && !tool.isEmpty()
                && origin.requiresCorrectToolForDrops()
                && !tool.isCorrectToolForDrops(origin);
    }

    /**
     * 执行一次连锁挖矿。
     *
     * @param includeOrigin true 表示起始方块还没被破坏（HEAD 接管模式），由这里一并处理；
     *                      false 表示起始方块已被原版破坏（RETURN 模式），只处理连锁部分
     * @return 是否真的接管了（false 时调用方应放行原版逻辑）
     */
    @Unique
    private boolean chainbreak$runChain(BlockPos pos, BlockState origin, boolean includeOrigin) {
        ChainBreakConfig.loadIfNeeded();

        ItemStack tool = this.player.getMainHandItem();

        // 1. 触发条件
        if (this.chainbreak$blocked(tool, origin) || !this.chainbreak$isTriggered()) {
            return false;
        }

        // 2. 冷却
        long now = System.currentTimeMillis();
        long cooldownMs = ChainBreakConfig.cooldownTicks * 50L;
        Long last = CHAINBREAK_LAST_USE.get(this.player.getUUID());
        if (cooldownMs > 0 && last != null && now - last < cooldownMs) {
            // 接管模式下静默放行（原版照常挖这一个方块），RETURN 模式下给个提示
            if (!includeOrigin) {
                this.chainbreak$feedback("§c连锁挖矿冷却中");
            }
            return false;
        }

        // 3. 经验预检（至少要够扣一个方块）
        double xpPerBlock = ChainBreakConfig.experiencePerBlock;
        if (xpPerBlock > 0.0D && this.player.totalExperience < Math.round(xpPerBlock)) {
            if (!includeOrigin) {
                this.chainbreak$feedback("§c经验不足");
            }
            return false;
        }

        // 4. 收集候选（形状可由按键或命令实时切换；此时起始方块可能还在，先算再破坏）
        List<BlockPos> candidates = ChainSearch.collect(this.level, ChainBreakState.effectiveShape(),
                pos, origin, this.player.getDirection(), tool);

        // 5. 逐个破坏
        // maxBlocks 指"这一轮一共挖多少块"，起始方块占掉 1 个名额，
        // 所以留给连锁部分的预算是 maxBlocks - 1（以前只卡连锁数，实际会多挖一块）
        int budget = Math.max(0, ChainBreakConfig.maxBlocks - 1);
        int chained = 0;
        this.chainbreak$replanted = 0;
        CHAINBREAK_BUSY.set(Boolean.TRUE);
        try {
            if (includeOrigin) {
                this.chainbreak$destroy(pos, tool);
            }
            for (BlockPos target : candidates) {
                if (chained >= budget) {
                    break;
                }
                if (this.chainbreak$toolSpent(tool)) {
                    break;
                }
                if (!ChainSearch.isCandidate(this.level, target, tool)) {
                    continue;
                }
                if (this.chainbreak$destroy(target, tool)) {
                    chained++;
                } else if (ChainBreakConfig.cancelOnBlockBreakFail) {
                    break;
                }
            }
        } finally {
            CHAINBREAK_BUSY.set(Boolean.FALSE);
        }

        // 一个都没连锁掉时：RETURN 模式不进入冷却（和原版挖单块一致）
        if (chained <= 0 && !includeOrigin) {
            return true;
        }

        // 6. 结算消耗与冷却（总数 = 连锁数 + 起始方块）
        int total = chained + 1;
        if (ChainBreakConfig.cooldownTicks > 0) {
            CHAINBREAK_LAST_USE.put(this.player.getUUID(), now);
        }
        if (ChainBreakConfig.exhaustionPerBlock > 0.0D) {
            this.player.causeFoodExhaustion((float) (ChainBreakConfig.exhaustionPerBlock * total));
        }
        if (xpPerBlock > 0.0D) {
            int cost = (int) Math.min(Math.round(xpPerBlock * total), this.player.totalExperience);
            if (cost > 0) {
                this.player.giveExperiencePoints(-cost);
            }
        }
        this.chainbreak$feedback("§a" + origin.getBlock().getName().getString() + " §f×" + total
                + (this.chainbreak$replanted > 0 ? " §7(补种 " + this.chainbreak$replanted + ")" : ""));
        ChainBreakState.lastBroken = total;
        return true;
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
     * 破坏一个方块并按 drop_mode 处理掉落。
     * normal 走原版 destroyBlock（原地掉落）；inventory 进背包；feet 掉到玩家脚下。
     */
    @Unique
    private boolean chainbreak$destroy(BlockPos pos, ItemStack tool) {
        BlockState before = this.level.getBlockState(pos);
        String mode = ChainBreakConfig.dropMode;
        if ("normal".equals(mode)) {
            boolean ok = this.player.gameMode.destroyBlock(pos);
            if (ok) {
                this.chainbreak$replant(pos, before);
            }
            return ok;
        }

        if (before.isAir()) {
            return false;
        }

        // 用原版 getDrops 计算掉落（传入玩家与工具以正确处理精准采集/时运）
        List<ItemStack> drops = Block.getDrops(before, this.level, pos,
                this.level.getBlockEntity(pos), this.player, tool);

        // 移除方块（不生成实体掉落）
        this.level.removeBlock(pos, false);
        // 原版的 destroyBlock 在移除方块之后会调 spawnAfterBreak —— 钻石、青金石、红石这些矿的
        // 经验球就是从这里出来的。自己走掉落链路（进背包 / 掉脚下）就绕过了那一步，
        // 所以必须手动补一次，否则挖矿一个经验球都不掉。
        before.spawnAfterBreak(this.level, pos, tool, true, this.player);
        if (this.level.getBlockState(pos).isAir()) {
            // 补种要在移除之后：作物本来就长在这一格上
            this.chainbreak$replant(pos, before);
        }

        for (ItemStack drop : drops) {
            if ("inventory".equals(mode)) {
                // Inventory#add 会消耗传入的 stack：背包只放得下一部分时剩余量留在 copy 里。
                // 用 copy 判断后把剩下的掉在地上，避免"原 stack 再掉一次"造成物品复制。
                ItemStack copy = drop.copy();
                this.player.getInventory().add(copy);
                if (!copy.isEmpty()) {
                    Containers.dropItemStack(this.level, this.player.getX(),
                            this.player.getY(), this.player.getZ(), copy);
                }
            } else {
                Containers.dropItemStack(this.level, this.player.getX(),
                        this.player.getY(), this.player.getZ(), drop);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 自动补种

    /**
     * 原位补种：把刚被挖掉的作物种回 {@code age = 0}、把树补回树苗，并从背包扣掉对应物品。
     *
     * <p>作物靠"哪个 BlockItem 放置出来就是这种作物"反查（小麦种子 → 小麦、
     * 胡萝卜 → 胡萝卜、下界疣 → 下界疣……），比手写映射表可靠。
     * 种之前先过 {@code canSurvive}，光照或支撑不够就不硬种，免得留下会自己掉的方块。</p>
     */
    @Unique
    private void chainbreak$replant(BlockPos pos, BlockState before) {
        if (!ChainBreakConfig.replant || before == null) {
            return;
        }
        if (!this.level.getBlockState(pos).isAir()) {
            return; // 这一格已经被别的东西占了
        }

        // 树：只在"树基"补一棵树苗
        if (ChainSearch.isLog(before)) {
            this.chainbreak$replantTree(pos, before);
            return;
        }

        IntegerProperty age = ChainSearch.ageProperty(before);
        if (age == null) {
            return; // 既不是作物也不是树干
        }
        Block crop = before.getBlock();
        BlockState young = crop.defaultBlockState();
        if (young.hasProperty(age)) {
            young = young.setValue(age, age.getPossibleValues().get(0));
        }
        if (!young.canSurvive(this.level, pos)) {
            return;
        }
        if (!this.chainbreak$takeItemFor(crop)) {
            return; // 没种子就别种
        }
        this.level.setBlock(pos, young, Block.UPDATE_ALL);
        this.chainbreak$replanted++;
    }

    /**
     * 树被砍掉后在树基补一棵树苗。
     *
     * <p>为什么要判断"西北角"：2×2 的大云杉 / 大丛林树基是 4 格原木，逐格补会种出 4 棵。
     * 只认「西边和北边都不是原木」的那一格，2×2 就只补 1 棵；普通单干树西/北本来就是空气，
     * 照常补。挖的顺序不定，最坏情况也只是这棵树少补一棵，不会多补。</p>
     */
    @Unique
    private void chainbreak$replantTree(BlockPos pos, BlockState log) {
        if (ChainSearch.isLog(this.level.getBlockState(pos.west()))
                || ChainSearch.isLog(this.level.getBlockState(pos.north()))) {
            return;
        }
        Block sapling = Replant.saplingFor(log.getBlock());
        if (sapling == null) {
            return; // 下界菌之类本来就没有对应树苗
        }
        BlockState young = sapling.defaultBlockState();
        if (!young.canSurvive(this.level, pos)) {
            return; // 地面不对或光照不够
        }
        if (!this.chainbreak$takeItemFor(sapling)) {
            return;
        }
        this.level.setBlock(pos, young, Block.UPDATE_ALL);
        this.chainbreak$replanted++;
    }

    /** 从背包里找一颗"放置出来就是该方块"的物品并扣掉（创造模式不扣）。 */
    @Unique
    private boolean chainbreak$takeItemFor(Block block) {
        if (this.player.isCreative()) {
            return true;
        }
        Inventory inventory = this.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }
}
