package dev.chainbreak.client;

import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreak;
import dev.chainbreak.ChainBreakState;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
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
 *
 * <p>布尔与枚举行点击切换；数字行点击后进入行内编辑（自己接管键盘，
 * 不依赖原版 {@code EditBox}，避免绘制层级和焦点带来的"点不动"问题）。</p>
 */
public class ChainBreakConfigListScreen extends Screen {

    private static final int LINE = 12;
    private static final int PAD = 6;
    private static final int PANEL_WIDTH = 320;
    private static final int BOX_WIDTH = 64;

    private static final int COL_TRUE = 0xFF55FF55;
    private static final int COL_FALSE = 0xFFFF5555;
    private static final int COL_NUM = 0xFFE29FFF;
    private static final int COL_ENUM = 0xFF55FFFF;
    private static final int COL_GROUP = 0xFF55FF55;
    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_READONLY = 0xFF808080;
    private static final int COL_BG = 0x90000000;
    private static final int COL_BOX = 0xFF000000;
    private static final int COL_BOX_EDIT = 0xFF303040;
    private static final int COL_BORDER = 0xFF8B8B8B;

    /** 一行配置。type: 0=分组 1=布尔 2=枚举 3=数字 4=只读 */
    private static final class Line {
        final int type;
        final String label;
        final Runnable click;
        final Supplier<String> value;
        final Supplier<Integer> color;
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
    /** 正在编辑的数字行与输入缓冲。 */
    private Line editing;
    private String buffer = "";

    public ChainBreakConfigListScreen(boolean server, Screen parent) {
        super(Component.literal(server ? "服务端设置" : "客户端设置"));
        this.server = server;
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        editing = null;
        buffer = "";

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
                    v -> ChainBreakConfig.maxBlocks = (int) clamp(v, 1, 1024));
            addNum("每方块饥饿消耗", () -> ChainBreakConfig.exhaustionPerBlock,
                    v -> ChainBreakConfig.exhaustionPerBlock = clamp(v, 0, 100));
            addNum("每方块经验消耗", () -> ChainBreakConfig.experiencePerBlock,
                    v -> ChainBreakConfig.experiencePerBlock = clamp(v, 0, 100));
            addNum("连锁冷却（刻）", () -> ChainBreakConfig.cooldownTicks,
                    v -> ChainBreakConfig.cooldownTicks = (int) clamp(v, 0, 72000));
            addNum("工具耐久保护", () -> ChainBreakConfig.preventToolBreak,
                    v -> ChainBreakConfig.preventToolBreak = (int) clamp(v, 0, 10000));
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
        int y = top + 16;
        for (Line line : lines) {
            line.y = y;
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

    private void addNum(String label, Supplier<Number> get, Consumer<Double> set) {
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
        applyEdit();
        ChainBreakConfig.save();
        this.onClose();
    }

    private void cancel() {
        editing = null;
        endTextInput();
        ChainBreakConfig.reload();
        this.onClose();
    }

    /**
     * 进入编辑：必须显式开启文本输入，否则 SDL 只发按键事件、不产生字符事件，
     * 表现就是"退格能删、打数字没反应"。
     */
    private void beginEditing(Line line) {
        applyEdit();
        if (this.minecraft != null) {
            this.minecraft.textInputManager().startTextInput(this);
        }
        editing = line;
        buffer = line.value.get();
    }

    private void endTextInput() {
        if (this.minecraft != null) {
            this.minecraft.textInputManager().stopTextInput(this);
        }
    }

    /** 把编辑中的文本写回配置。 */
    private void applyEdit() {
        Line target = editing;
        String text = buffer.trim();
        editing = null;
        buffer = "";
        if (target == null || target.commit == null || text.isEmpty()) {
            return;
        }
        endTextInput();
        try {
            double value = Double.parseDouble(text);
            target.commit.accept(value);
            ChainBreak.LOGGER.log(System.Logger.Level.INFO, "配置已修改：" + target.label + " = " + text);
        } catch (NumberFormatException ignored) {
            // 非法输入保持原值
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        // 底色先画，否则会盖住控件
        g.fill(left - PAD, this.panelTop - PAD, left + PANEL_WIDTH + PAD, this.panelBottom, COL_BG);

        super.extractRenderState(g);

        g.text(this.font, this.title, left, this.panelTop, COL_GROUP);

        for (Line line : lines) {
            if (line.type == 0) {
                g.text(this.font, Component.literal("▼ " + line.label), left, line.y, COL_GROUP);
                continue;
            }
            g.text(this.font, Component.literal(line.label), left, line.y, COL_LABEL);
            if (line.type == 3) {
                drawBox(g, line, left);
            } else {
                String v = line.value.get();
                g.text(this.font, Component.literal(v),
                        left + PANEL_WIDTH - PAD - this.font.width(v), line.y, line.color.get());
            }
        }
    }

    /** 数字行右侧的小输入框：自己画，自己管光标。 */
    private void drawBox(GuiGraphicsExtractor g, Line line, int left) {
        int x = left + PANEL_WIDTH - PAD - BOX_WIDTH;
        int top = line.y - 1;
        int bottom = top + 12;
        boolean active = editing == line;
        g.fill(x, top, x + BOX_WIDTH, bottom, active ? COL_BOX_EDIT : COL_BOX);
        g.fill(x, top, x + BOX_WIDTH, top + 1, COL_BORDER);
        g.fill(x, bottom - 1, x + BOX_WIDTH, bottom, COL_BORDER);
        g.fill(x, top, x + 1, bottom, COL_BORDER);
        g.fill(x + BOX_WIDTH - 1, top, x + BOX_WIDTH, bottom, COL_BORDER);

        String text = active ? buffer : line.value.get();
        if (active && (System.currentTimeMillis() / 500) % 2 == 0) {
            text = text + "_";
        }
        int maxWidth = BOX_WIDTH - 6;
        while (this.font.width(text) > maxWidth && text.length() > 1) {
            text = text.substring(1);
        }
        g.text(this.font, Component.literal(text), x + 3, line.y + 1,
                active ? 0xFFFFFF55 : 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        if (event.isLeftMouse() && event.x() >= left && event.x() <= left + PANEL_WIDTH) {
            for (Line line : lines) {
                if (event.y() < line.y || event.y() >= line.y + LINE) {
                    continue;
                }
                if (line.click != null) {
                    line.click.run();
                    return true;
                }
                if (line.type == 3) {
                    beginEditing(line);
                    return true;
                }
            }
        }
        applyEdit();
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (editing != null) {
            if (key == InputConstants.KEY_ESCAPE) {
                editing = null;
                buffer = "";
                endTextInput();
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                applyEdit();
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                if (!buffer.isEmpty()) {
                    buffer = buffer.substring(0, buffer.length() - 1);
                }
                return true;
            }
            // 兜底：直接把数字键的扫描码映射成字符（不依赖文本输入是否已开启）
            char mapped = digitFor(key);
            if (mapped != 0 && buffer.length() < 16) {
                buffer = buffer + mapped;
            }
            // 编辑期间吞掉其它按键，避免误触发界面行为
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            this.commit();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 把数字 / 负号 / 小数点的按键映射成字符，认不出返回 0。 */
    private static char digitFor(int key) {
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
            return (char) ('1' + (key - InputConstants.KEY_1));
        }
        if (key == InputConstants.KEY_0) {
            return '0';
        }
        if (key == InputConstants.KEY_MINUS) {
            return '-';
        }
        if (key == InputConstants.KEY_PERIOD) {
            return '.';
        }
        return 0;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing != null) {
            String typed = event.codepointAsString();
            if (typed.length() == 1) {
                char c = typed.charAt(0);
                if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                    if (buffer.length() < 16) {
                        buffer = buffer + c;
                    }
                }
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        endTextInput();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
