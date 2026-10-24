package dev.chainbreak.client;

import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreakState;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 配置列表界面（对标 FTB Library 的配置编辑器样式）：
 * 分组标题（绿色）、配置行（左名称 / 右彩色值）、底部确认 / 取消。
 * 布尔与枚举行点击切换；数字行右侧有输入框，点确认时提交。
 */
public class ChainBreakConfigListScreen extends Screen {

    private static final int LINE = 12;
    private static final int PAD = 6;
    private static final int PANEL_WIDTH = 320;

    private static final int COL_TRUE = 0xFF55FF55;
    private static final int COL_FALSE = 0xFFFF5555;
    private static final int COL_NUM = 0xFFE29FFF;
    private static final int COL_ENUM = 0xFF55FFFF;
    private static final int COL_GROUP = 0xFF55FF55;
    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_READONLY = 0xFF808080;
    private static final int COL_BG = 0x90000000;

    /** 一行配置。type: 0=分组 1=布尔 2=枚举 3=数字 4=只读 */
    private static final class Line {
        final int type;
        final String label;
        final Runnable click;
        final Supplier<String> value;
        final Supplier<Integer> color;
        EditBox box;
        Consumer<Double> commit;
        int y;

        Line(int type, String label, Runnable click, Supplier<String> value, Supplier<Integer> color) {
            this.type = type;
            this.label = label;
            this.click = click;
            this.value = value;
            this.color = color;
        }
    }

    private final boolean server;
    private final Screen parent;
    private final List<Line> lines = new ArrayList<>();
    private int panelTop;
    private int panelBottom;

    public ChainBreakConfigListScreen(boolean server, Screen parent) {
        super(Component.literal(server ? "服务端设置" : "客户端设置"));
        this.server = server;
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();

        if (server) {
            addGroup("功能");
            addEnum("触发方式", () -> ChainBreakConfig.trigger,
                    v -> ChainBreakConfig.trigger = v, new String[]{"key", "sneak", "both"},
                    new String[]{"按键", "潜行", "两者"});
            addBool("需要工具才能连锁", () -> ChainBreakConfig.requireTool,
                    v -> ChainBreakConfig.requireTool = v);
            addBool("需要对应工具", () -> ChainBreakConfig.requireValidToolForBlock,
                    v -> ChainBreakConfig.requireValidToolForBlock = v);
            addBool("形状模式破坏任意方块", () -> ChainBreakConfig.shapedAnyBlock,
                    v -> ChainBreakConfig.shapedAnyBlock = v);
            addBool("无形状模式破坏其他方块", () -> ChainBreakConfig.shapelessAnyBlock,
                    v -> ChainBreakConfig.shapelessAnyBlock = v);

            addGroup("消耗与限制");
            addNum("单次最大方块数", () -> ChainBreakConfig.maxBlocks,
                    v -> ChainBreakConfig.maxBlocks = (int) clamp(v, 1, 1024), 1, 1024, true);
            addNum("每方块饥饿消耗", () -> ChainBreakConfig.exhaustionPerBlock,
                    v -> ChainBreakConfig.exhaustionPerBlock = clamp(v, 0, 100), 0, 100, false);
            addNum("每方块经验消耗", () -> ChainBreakConfig.experiencePerBlock,
                    v -> ChainBreakConfig.experiencePerBlock = clamp(v, 0, 100), 0, 100, false);
            addNum("连锁冷却（刻）", () -> ChainBreakConfig.cooldownTicks,
                    v -> ChainBreakConfig.cooldownTicks = (int) clamp(v, 0, 72000), 0, 72000, true);
            addNum("工具耐久保护", () -> ChainBreakConfig.preventToolBreak,
                    v -> ChainBreakConfig.preventToolBreak = (int) clamp(v, 0, 10000), 0, 10000, true);
            addEnum("掉落方式", () -> ChainBreakConfig.dropMode,
                    v -> ChainBreakConfig.dropMode = v,
                    new String[]{"normal", "inventory", "feet"},
                    new String[]{"原地掉落", "进背包", "脚下掉落"});

            addGroup("杂项");
            addBool("破坏失败时取消", () -> ChainBreakConfig.cancelOnBlockBreakFail,
                    v -> ChainBreakConfig.cancelOnBlockBreakFail = v);
            addBool("显示连锁提示", () -> ChainBreakConfig.feedback,
                    v -> ChainBreakConfig.feedback = v);
            addReadonly("方块黑名单",
                    () -> ChainBreakConfig.blacklist.size() + " 项（/chainbreak blacklist 管理）");
        } else {
            addGroup("常规");
            addEnum("形状", () -> ChainBreakState.effectiveShape(),
                    v -> ChainBreakState.setShape(v), ChainBreakState.SHAPES, ChainBreakState.SHAPE_NAMES);
        }

        // 布局：面板居中
        int contentHeight = 16 + lines.stream().mapToInt(l -> l.type == 0 ? LINE + 2 : LINE).sum();
        int top = Math.max(18, this.height / 2 - (contentHeight + 34) / 2);
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int boxWidth = 64;

        int y = top + 16;
        for (Line line : lines) {
            line.y = y;
            if (line.type == 3) {
                EditBox box = new EditBox(this.font, left + PANEL_WIDTH - PAD - boxWidth,
                        y - 1, boxWidth, 11, Component.literal(line.label));
                box.setValue(line.value.get());
                box.setMaxLength(16);
                line.box = box;
                addRenderableWidget(box);
            }
            y += line.type == 0 ? LINE + 2 : LINE;
        }

        int bottom = y + 6;
        this.panelTop = top;
        this.panelBottom = bottom + 26;

        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("✔ 确认"), b -> this.commit())
                .bounds(cx - 104, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("✘ 取消"), b -> this.cancel())
                .bounds(cx + 4, bottom, 100, 20).build());
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void addGroup(String label) {
        lines.add(new Line(0, label, null, null, null));
    }

    private void addBool(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        lines.add(new Line(1, label,
                () -> set.accept(!get.get()),
                () -> get.get() ? "开启" : "关闭",
                () -> get.get() ? COL_TRUE : COL_FALSE));
    }

    private void addEnum(String label, Supplier<String> get, Consumer<String> set,
                         String[] values, String[] display) {
        lines.add(new Line(2, label,
                () -> {
                    int i = 0;
                    for (int k = 0; k < values.length; k++) {
                        if (values[k].equals(get.get())) {
                            i = k;
                            break;
                        }
                    }
                    set.accept(values[(i + 1) % values.length]);
                },
                () -> display[ChainBreakState.indexOf(values, get.get())],
                () -> COL_ENUM));
    }

    private void addNum(String label, Supplier<Number> get, Consumer<Double> set,
                        double min, double max, boolean integral) {
        Line line = new Line(3, label, null,
                () -> fmt(get.get().doubleValue()), () -> COL_NUM);
        line.commit = set;
        lines.add(line);
    }

    private void addReadonly(String label, Supplier<String> value) {
        lines.add(new Line(4, label, null, value, () -> COL_READONLY));
    }

    private static String fmt(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v)
                ? Long.toString((long) v) : Double.toString(v);
    }

    private void commit() {
        for (Line line : lines) {
            if (line.type == 3 && line.box != null && line.commit != null) {
                try {
                    line.commit.accept(Double.parseDouble(line.box.getValue().trim()));
                } catch (NumberFormatException ignored) {
                    // 非法输入保持原值
                }
            }
        }
        ChainBreakConfig.save();
        this.onClose();
    }

    private void cancel() {
        ChainBreakConfig.reload();
        this.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        // 面板底色必须先画：否则会盖住输入框，看起来"输入框没有边框、点不动"
        g.fill(left - PAD, this.panelTop - PAD, left + PANEL_WIDTH + PAD, this.panelBottom, COL_BG);

        // 控件（含输入框）画在底色之上
        super.extractRenderState(g);

        g.text(this.font, this.title, left, this.panelTop, COL_GROUP);

        for (Line line : lines) {
            if (line.type == 0) {
                g.text(this.font, Component.literal("▼ " + line.label), left, line.y, COL_GROUP);
            } else {
                g.text(this.font, Component.literal(line.label), left, line.y, COL_LABEL);
                // 数字行的值由输入框自己显示，这里不重复画（否则会盖在框上）
                if (line.type != 3) {
                    String v = line.value.get();
                    g.text(this.font, Component.literal(v),
                            left + PANEL_WIDTH - PAD - this.font.width(v), line.y, line.color.get());
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        if (event.isLeftMouse()
                && event.x() >= left && event.x() <= left + PANEL_WIDTH) {
            for (Line line : lines) {
                if (event.y() < line.y || event.y() >= line.y + LINE) {
                    continue;
                }
                if (line.click != null) {
                    line.click.run();
                    return true;
                }
                if (line.type == 3 && line.box != null) {
                    // 点这一行任意位置都能编辑，不用精确点到小框
                    for (Line other : lines) {
                        if (other != line && other.box != null) {
                            other.box.setFocused(false);
                        }
                    }
                    line.box.setFocused(true);
                    line.box.setCursorPosition(line.box.getValue().length());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            this.commit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
