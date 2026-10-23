package dev.chainbreak.client;

import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreakState;
import dev.chainbreak.Shapes;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
 * 连锁挖矿 HUD（对标 FTB Ultimine 的左上角面板）。
 * <ul>
 *   <li>按住激活键：左上角半透明面板，显示标题、当前形状、预估采掘数；</li>
 *   <li>按住激活键 + 潜行键：面板展开形状列表，当前形状黄色高亮，滚轮切换。</li>
 * </ul>
 * "采掘 N 方块"是预估值：根据准星指向的方块，按当前形状与过滤规则实时计算。
 */
public class ChainBreakHud implements HudElement {

    private static final int BG = 0x90000000;
    private static final int PAD = 5;
    private static final int LINE = 11;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc == null ? null : mc.font;
        if (font == null || mc.player == null || !ChainBreakState.keyDown) {
            return;
        }

        // 固定用物理 Shift 键展开菜单（不随自定义潜行键变化）
        boolean menuOpen = isShiftHeld();
        String[] names = ChainBreakState.SHAPE_NAMES;
        int idx = ChainBreakState.index();

        int lines = menuOpen ? 2 + names.length + 1 + 1 : 4;
        int panelWidth = 168;
        int panelHeight = PAD + lines * LINE + PAD - 3;

        g.fill(4, 4, 4 + panelWidth, 4 + panelHeight, BG);

        int x = 4 + PAD;
        int y = 4 + PAD;

        g.text(font, Component.literal("连锁挖矿 已激活"), x, y, 0xFFFFFFFF);
        y += LINE;

        if (menuOpen) {
            for (int i = 0; i < names.length; i++) {
                boolean selected = i == idx;
                Component label = selected
                        ? Component.literal("▶ " + names[i]).withStyle(ChatFormatting.YELLOW)
                        : Component.literal(names[i]).withStyle(ChatFormatting.GRAY);
                g.text(font, label, x, y, 0xFFFFFFFF);
                y += LINE;
            }
            g.text(font, Component.literal("按住Shift+滚轮切换")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), x, y, 0xFFFFFFFF);
            y += LINE;
        } else {
            g.text(font, Component.literal("按住Shift打开菜单")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), x, y, 0xFFFFFFFF);
            y += LINE;
        }

        // 预估：准星指向的方块按当前形状会破坏多少个（含起始方块）
        int estimate = estimateTargeted(mc);
        String blockName = targetBlockName(mc);
        g.text(font, Component.literal("采掘 " + estimate + " 方块 · " + blockName)
                .withStyle(ChatFormatting.GRAY), x, y, 0xFFFFFFFF);
        y += LINE;

        // 最低工具要求：满足 ✓，不满足 ✗
        g.text(font, toolRequirement(mc), x, y, 0xFFFFFFFF);
    }

    /** 准星指向方块的最低工具要求 + 当前工具是否满足（✓/✗）。 */
    private Component toolRequirement(Minecraft mc) {
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult bhr)
                || bhr.getType() != HitResult.Type.BLOCK) {
            return Component.literal("工具：—").withStyle(ChatFormatting.GRAY);
        }
        BlockState state = mc.level.getBlockState(bhr.getBlockPos());
        if (state.isAir()) {
            return Component.literal("工具：—").withStyle(ChatFormatting.GRAY);
        }
        ItemStack tool = mc.player.getMainHandItem();
        boolean needsTool = state.requiresCorrectToolForDrops();
        boolean ok = !needsTool || tool.isCorrectToolForDrops(state);

        String mark = ok ? "✓" : "✗";
        ChatFormatting color = ok ? ChatFormatting.GREEN : ChatFormatting.RED;
        String desc = needsTool ? "需要工具" : "无需工具";
        return Component.literal("工具：" + mark + " " + desc).withStyle(color);
    }

    /** 准星指向方块的显示名（未指向方块时返回"空气"）。 */
    private String targetBlockName(Minecraft mc) {
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult bhr)
                || bhr.getType() != HitResult.Type.BLOCK) {
            return "空气";
        }
        BlockState state = mc.level.getBlockState(bhr.getBlockPos());
        if (state.isAir()) {
            return "空气";
        }
        return state.getBlock().getName().getString();
    }

    /** 物理 Shift 键是否被按住（与自定义潜行键无关）。 */
    private static boolean isShiftHeld() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    /** 预估准星指向方块被连锁挖掉的总数（含起始方块，逻辑与服务端一致）。 */
    private int estimateTargeted(Minecraft mc) {
        List<BlockPos> candidates = ChainBreakPreview.candidates(mc);
        if (candidates == null) {
            return 0; // 起始方块不合格
        }
        // 加上玩家手动挖掉的起始方块（FTB 显示的是总数）
        return candidates.size() + 1;
    }
}
