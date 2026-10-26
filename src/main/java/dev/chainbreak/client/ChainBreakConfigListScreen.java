package dev.chainbreak.client;

import dev.chainbreak.ChainBreak;
import dev.chainbreak.ChainBreakConfig;
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
 * 配置界面（对标 FTB Library 的配置编辑器样式）：
 * 分组标题（绿色）、配置行（左名称 / 右值）、底部按钮。
 *
 * <p>以前分成「客户端设置」和「服务端设置」两个页面，现在合成一页——
 * 形状（客户端）排在「常规」里，其余服务端项按功能继续分组，
 * 内容超出一屏时可以滚轮滚动。</p>
 *
 * <p>布尔与枚举行点击切换；数字行是真·行内编辑：点一下定位光标、按住拖动可以框选，
 * 打字替换选区，方向键 / Home / End 移动光标。改完不用按回车——点别处或关界面就生效并落盘。</p>
 */
public class ChainBreakConfigListScreen extends Screen {

    private static final int LINE = 12;
    private static final int PAD = 6;
    private static final int PANEL_WIDTH = 320;
    private static final int BOX_WIDTH = 64;
    /** 标题占的高度。 */
    private static final int TITLE_HEIGHT = 12;

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
    private static final int COL_SELECTION = 0x804A80FF;
    private static final int COL_CARET = 0xFFFFFFFF;
    private static final int COL_SCROLLBAR = 0xFF707070;
    private static final int COL_SCROLLBAR_BG = 0x40FFFFFF;

    /** 一行配置。type: 0=分组 1=布尔 2=枚举 3=数字 4=只读 */
    private static final class Line {
        final int type;
        final String label;
        final Runnable click;
        final Supplier<String> value;
        final Supplier<Integer> color;
        Consumer<Double> commit;
        /** 相对内容顶部的纵坐标（不含滚动偏移）。 */
        int y;

        Line(int type, String label, Runnable click, Supplier<String> value, Supplier<Integer> color) {
            this.type = type;
            this.label = label;
            this.click = click;
            this.value = value;
            this.color = color;
        }
    }

    private final Screen parent;
    private final List<Line> lines = new ArrayList<>();
    private int panelTop;
    private int panelBottom;
    /** 列表可视区的上下边界（屏幕坐标）。 */
    private int listTop;
    private int listBottom;
    /** 内容总高度与可视高度。 */
    private int contentHeight;
    private int viewHeight;
    /** 当前滚动偏移（像素），0 表示顶部。 */
    private int scroll;

    /** 正在编辑的数字行 + 文本编辑状态。 */
    private Line editing;
    private String buffer = "";
    private int caret;
    /** 选区起点，-1 表示没有选区。 */
    private int selectionAnchor = -1;
    private boolean selecting;

    public ChainBreakConfigListScreen(Screen parent) {
        super(Component.literal("连锁挖矿设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        clearEditing();

        addGroup("常规");
        addEnum("形状", () -> ChainBreakState.effectiveShape(),
                v -> ChainBreakState.setShape(v), ChainBreakState.SHAPES, ChainBreakState.SHAPE_NAMES);

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

        addGroup("农作物");
        addBool("只连锁同成熟度的作物", () -> ChainBreakConfig.cropSameAge,
                v -> ChainBreakConfig.cropSameAge = v);
        addBool("连锁收割后自动补种", () -> ChainBreakConfig.replant,
                v -> ChainBreakConfig.replant = v);
        addBool("连锁一键种植（右键种子）", () -> ChainBreakConfig.chainPlant,
                v -> ChainBreakConfig.chainPlant = v);

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

        layout();
    }

    /** 算好内容高度、可视区、滚动上限和底部按钮位置。 */
    private void layout() {
        contentHeight = lines.stream().mapToInt(l -> l.type == 0 ? LINE + 2 : LINE).sum();

        // 面板除列表外要占的高度：上下留白 + 标题 + 标题间距 + 按钮间距 + 按钮
        int reserved = PAD + TITLE_HEIGHT + 2 + 4 + 20 + PAD;
        int viewMax = Math.max(LINE, this.height - reserved);
        this.viewHeight = Math.min(contentHeight, viewMax);

        // 装得下就居中，装不下就贴顶
        int blockHeight = this.viewHeight + reserved;
        int top = Math.max(PAD, (this.height - blockHeight) / 2);

        this.panelTop = top;
        this.listTop = top + TITLE_HEIGHT + 2;
        this.listBottom = this.listTop + this.viewHeight;

        int y = 0;
        for (Line line : lines) {
            line.y = y;
            y += line.type == 0 ? LINE + 2 : LINE;
        }

        this.scroll = clampInt(this.scroll, 0, maxScroll());
        int buttonY = this.listBottom + 4;
        this.panelBottom = buttonY + 20 + PAD;

        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("✔ 完成"), b -> this.closeWithSave())
                .bounds(cx - 104, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("↺ 恢复默认"), b -> this.restoreDefaults())
                .bounds(cx + 4, buttonY, 100, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - viewHeight);
    }

    /** 一行在屏幕上的纵坐标（含滚动偏移）。 */
    private int screenY(Line line) {
        return this.listTop + line.y - this.scroll;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
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

    // ---------------------------------------------------------------- 编辑状态

    private void clearEditing() {
        editing = null;
        buffer = "";
        caret = 0;
        selectionAnchor = -1;
        selecting = false;
    }

    /** 进入编辑：必须显式开启文本输入，否则 SDL 只发按键事件、不产生字符事件。 */
    private void beginEditing(Line line) {
        applyEdit();
        if (this.minecraft != null) {
            this.minecraft.textInputManager().startTextInput(this);
        }
        editing = line;
        buffer = line.value.get();
        caret = buffer.length();
        selectionAnchor = -1;
        selecting = true;
    }

    private void endTextInput() {
        if (this.minecraft != null) {
            this.minecraft.textInputManager().stopTextInput(this);
        }
    }

    private boolean hasSelection() {
        return selectionAnchor >= 0 && selectionAnchor != caret;
    }

    private int selStart() {
        int n = buffer.length();
        int v = hasSelection() ? Math.min(selectionAnchor, caret) : caret;
        return Math.max(0, Math.min(n, v));
    }

    private int selEnd() {
        int n = buffer.length();
        int v = hasSelection() ? Math.max(selectionAnchor, caret) : caret;
        return Math.max(0, Math.min(n, v));
    }

    /**
     * 把光标和选区锚点收回 [0, buffer.length()] 并收拾掉退化选区。
     *
     * <p>为什么必须有这一步：点进输入框时锚点和光标是同一个位置（等着往后拖选），
     * 此时 {@link #hasSelection()} 返回 false。这时按退格只会让光标后退一格，锚点留在原处，
     * 两者就"错开"成了一个假的选区，而且末端越过了文本长度 —— 渲染时
     * {@code buffer.substring(0, selEnd())} 直接抛
     * {@code StringIndexOutOfBoundsException: Range [0, 2) out of bounds for length 1}
     * 把游戏崩掉。所以每次改完 buffer 都归一化一次。</p>
     */
    private void normalizeEditingState() {
        int n = buffer.length();
        caret = Math.max(0, Math.min(n, caret));
        if (selectionAnchor > n) {
            selectionAnchor = n;
        }
        if (selectionAnchor == caret) {
            // 收成一点就等于没有选区，顺便把锚点丢掉，免得下次再劈叉
            selectionAnchor = -1;
        }
    }

    private void deleteSelection() {
        if (!hasSelection()) {
            return;
        }
        int from = selStart();
        int to = selEnd();
        buffer = buffer.substring(0, from) + buffer.substring(to);
        caret = from;
        selectionAnchor = -1;
    }

    /** 把编辑中的文本写回配置并落盘。 */
    private void applyEdit() {
        Line target = editing;
        String text = buffer.trim();
        clearEditing();
        endTextInput();
        if (target == null || target.commit == null || text.isEmpty()) {
            return;
        }
        try {
            target.commit.accept(Double.parseDouble(text));
            ChainBreakConfig.save();
            // 记"真正生效的值"而不是输入原文：各字段的 setter 会 clamp（比如最大方块数卡在 1~1024），
            // 照着原文记会误导排查——曾经就有人根据日志以为 max_blocks 真的是 9999
            String applied = target.value.get();
            ChainBreak.LOGGER.log(System.Logger.Level.INFO,
                    "配置已修改：" + target.label + " = " + applied
                            + (applied.equals(text) ? "" : "（输入 " + text + "，超出范围已修正）"));
        } catch (NumberFormatException ignored) {
            // 非法输入保持原值
        }
    }

    private void cancelEdit() {
        clearEditing();
        endTextInput();
    }

    private void closeWithSave() {
        applyEdit();
        ChainBreakConfig.save();
        this.onClose();
    }

    private void restoreDefaults() {
        cancelEdit();
        ChainBreakConfig.restoreDefaults();
        // 形状现在也在这页里，恢复默认要连运行时的覆盖值一起清掉，否则界面上还显示旧形状
        ChainBreakState.shapeOverride = null;
        ChainBreakConfig.save();
        this.scroll = 0;
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new ChainBreakConfigListScreen(this.parent));
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void extractRenderState(GuiGraphicsExtractor g) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        // 底色先画，否则会盖住控件
        g.fill(left - PAD, this.panelTop - PAD, left + PANEL_WIDTH + PAD, this.panelBottom, COL_BG);

        super.extractRenderState(g);

        g.text(this.font, this.title, left, this.panelTop, COL_GROUP);

        // 列表区裁剪：内容超出可视高度时，超出的部分不画出来
        g.enableScissor(left - PAD, this.listTop, left + PANEL_WIDTH + PAD, this.listBottom);
        for (Line line : lines) {
            int y = screenY(line);
            if (y + LINE < this.listTop || y > this.listBottom) {
                continue;
            }
            if (line.type == 0) {
                g.text(this.font, Component.literal("▼ " + line.label), left, y, COL_GROUP);
                continue;
            }
            g.text(this.font, Component.literal(line.label), left, y, COL_LABEL);
            if (line.type == 3) {
                drawBox(g, line, y);
            } else {
                String v = line.value.get();
                g.text(this.font, Component.literal(v),
                        left + PANEL_WIDTH - PAD - this.font.width(v), y, line.color.get());
            }
        }
        g.disableScissor();

        drawScrollbar(g, left);
    }

    /** 内容装得下就不画；装不下时在面板右缘画一条细滚动条。 */
    private void drawScrollbar(GuiGraphicsExtractor g, int left) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int x = left + PANEL_WIDTH - 1;
        g.fill(x, this.listTop, x + 2, this.listBottom, COL_SCROLLBAR_BG);
        int track = this.listBottom - this.listTop;
        int thumb = Math.max(16, track * this.viewHeight / Math.max(1, contentHeight));
        int thumbY = this.listTop + (track - thumb) * this.scroll / max;
        g.fill(x, thumbY, x + 2, thumbY + thumb, COL_SCROLLBAR);
    }

    private int boxLeft() {
        return this.width / 2 - PANEL_WIDTH / 2 + PANEL_WIDTH - PAD - BOX_WIDTH;
    }

    /** 保证光标可见的水平滚动量（渲染和命中测试共用，避免两边不一致）。 */
    private int scrollPx() {
        if (editing == null) {
            return 0;
        }
        int caretW = this.font.width(buffer.substring(0, Math.max(0, Math.min(caret, buffer.length()))));
        return Math.max(0, caretW - (BOX_WIDTH - 8));
    }

    private void drawBox(GuiGraphicsExtractor g, Line line, int screenY) {
        int x = boxLeft();
        int top = screenY - 1;
        int bottom = top + 12;
        boolean active = editing == line;

        g.fill(x, top, x + BOX_WIDTH, bottom, active ? COL_BOX_EDIT : COL_BOX);
        g.fill(x, top, x + BOX_WIDTH, top + 1, COL_BORDER);
        g.fill(x, bottom - 1, x + BOX_WIDTH, bottom, COL_BORDER);
        g.fill(x, top, x + 1, bottom, COL_BORDER);
        g.fill(x + BOX_WIDTH - 1, top, x + BOX_WIDTH, bottom, COL_BORDER);

        int textY = screenY + 1;
        if (!active) {
            String shown = line.value.get();
            while (this.font.width(shown) > BOX_WIDTH - 8 && shown.length() > 1) {
                shown = shown.substring(1);
            }
            g.text(this.font, Component.literal(shown), x + 3, textY, 0xFFFFFFFF);
            return;
        }

        int scrollX = scrollPx();
        int innerX = x + 3 - scrollX;
        g.enableScissor(x + 1, top + 1, x + BOX_WIDTH - 1, bottom - 1);
        if (hasSelection()) {
            int x1 = innerX + this.font.width(buffer.substring(0, selStart()));
            int x2 = innerX + this.font.width(buffer.substring(0, selEnd()));
            g.fill(x1, top + 2, x2, bottom - 2, COL_SELECTION);
        }
        g.text(this.font, Component.literal(buffer), innerX, textY, 0xFFFFFF55);
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = innerX + this.font.width(buffer.substring(0, Math.max(0, Math.min(caret, buffer.length()))));
            g.fill(caretX, top + 2, caretX + 1, bottom - 2, COL_CARET);
        }
        g.disableScissor();
    }

    // ---------------------------------------------------------------- 输入

    /** 鼠标横坐标换算成字符下标。 */
    private int charIndexAt(double mouseX) {
        int innerX = boxLeft() + 3 - scrollPx();
        double rel = mouseX - innerX;
        int acc = 0;
        for (int i = 0; i < buffer.length(); i++) {
            int w = this.font.width(String.valueOf(buffer.charAt(i)));
            if (rel < acc + w / 2.0) {
                return i;
            }
            acc += w;
        }
        return buffer.length();
    }

    /** 屏幕纵坐标换算成内容纵坐标（去掉滚动偏移）。 */
    private double contentY(double mouseY) {
        return mouseY - this.listTop + this.scroll;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        boolean insidePanel = event.x() >= left && event.x() <= left + PANEL_WIDTH;
        boolean insideList = event.y() >= this.listTop && event.y() <= this.listBottom;
        if (event.isLeftMouse() && insidePanel && insideList) {
            double cy = contentY(event.y());
            for (Line line : lines) {
                if (cy < line.y || cy >= line.y + LINE) {
                    continue;
                }
                if (line.click != null) {
                    applyEdit();
                    line.click.run();
                    ChainBreakConfig.save();
                    return true;
                }
                if (line.type == 3) {
                    if (editing != line) {
                        beginEditing(line);
                    }
                    // 点哪里光标就落哪里，并从这里开始框选
                    caret = charIndexAt(event.x());
                    selectionAnchor = caret;
                    selecting = true;
                    return true;
                }
                if (line.type == 0 || line.type == 4) {
                    applyEdit();
                    return true;
                }
            }
        }
        applyEdit();
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (editing != null && selecting) {
            caret = charIndexAt(event.x());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        selecting = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        boolean overList = mouseX >= left - PAD && mouseX <= left + PANEL_WIDTH + PAD
                && mouseY >= this.listTop && mouseY <= this.listBottom;
        if (overList && maxScroll() > 0 && scrollY != 0.0D) {
            // 滚之前先把正在编辑的值落盘，免得盒子跟着滚走导致串行
            applyEdit();
            this.scroll = clampInt(this.scroll - (int) Math.round(scrollY * LINE), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (editing != null) {
            switch (key) {
                case InputConstants.KEY_ESCAPE -> {
                    cancelEdit();
                    return true;
                }
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    applyEdit();
                    return true;
                }
                case InputConstants.KEY_BACKSPACE -> {
                    if (hasSelection()) {
                        deleteSelection();
                    } else if (caret > 0) {
                        buffer = buffer.substring(0, caret - 1) + buffer.substring(caret);
                        caret--;
                    }
                    normalizeEditingState();
                    return true;
                }
                case InputConstants.KEY_DELETE -> {
                    if (hasSelection()) {
                        deleteSelection();
                    } else if (caret < buffer.length()) {
                        buffer = buffer.substring(0, caret) + buffer.substring(caret + 1);
                    }
                    normalizeEditingState();
                    return true;
                }
                case InputConstants.KEY_LEFT -> {
                    caret = Math.max(0, hasSelection() ? selStart() : caret - 1);
                    selectionAnchor = -1;
                    return true;
                }
                case InputConstants.KEY_RIGHT -> {
                    caret = Math.min(buffer.length(), hasSelection() ? selEnd() : caret + 1);
                    selectionAnchor = -1;
                    return true;
                }
                case InputConstants.KEY_HOME -> {
                    caret = 0;
                    selectionAnchor = -1;
                    return true;
                }
                case InputConstants.KEY_END -> {
                    caret = buffer.length();
                    selectionAnchor = -1;
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            closeWithSave();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing != null) {
            String typed = event.codepointAsString();
            if (typed.length() == 1) {
                char c = typed.charAt(0);
                boolean allowed = (c >= '0' && c <= '9') || c == '.' || c == '-';
                if (allowed) {
                    deleteSelection();
                    if (buffer.length() < 16) {
                        caret = Math.max(0, Math.min(buffer.length(), caret));
                        buffer = buffer.substring(0, caret) + c + buffer.substring(caret);
                        caret++;
                    }
                    selectionAnchor = -1;
                    normalizeEditingState();
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
        applyEdit();
        endTextInput();
        ChainBreakConfig.save();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}