package dev.chainbreak;

/**
 * 客户端与服务端共享的运行时状态。
 * <p>
 * 单人游戏里客户端与集成服务端在同一个 JVM，所以按键状态、形状选择直接用静态字段传递；
 * 在真正的多人服务器上客户端这边的值传不过去，此时请把配置里的 trigger 设为 sneak 或 both。
 */
public final class ChainBreakState {

    /** 形状 id 列表，顺序与 FTB Ultimine 的形状表一致。 */
    public static final String[] SHAPES = {
            "shapeless", "tunnel_1x1", "tunnel_3x3", "square_3x3", "stair_up", "stair_down"
    };

    /** 形状显示名（用于快捷栏提示）。 */
    public static final String[] SHAPE_NAMES = {
            "无形状", "小型通道", "大型通道", "小型方形", "逃生通道", "采矿通道"
    };

    /** 连锁按键是否按下（由客户端每刻刷新）。 */
    public static volatile boolean keyDown = false;

    /** 上一次连锁挖矿的方块数（HUD 显示用）。 */
    public static volatile int lastBroken = 0;

    /** 客户端切换过的形状；null 表示用配置文件里的 shape。 */
    public static volatile String shapeOverride = null;

    private ChainBreakState() {
    }

    /** 当前生效的形状 id。 */
    public static String effectiveShape() {
        String s = shapeOverride;
        return s != null ? s : ChainBreakConfig.shape;
    }

    /** 当前形状在列表中的索引（0 起），找不到返回 0。 */
    public static int index() {
        return indexOf(SHAPES, effectiveShape());
    }

    /** 当前生效的形状显示名。 */
    public static String effectiveShapeName() {
        return displayName(effectiveShape());
    }

    /** 形状 id 转显示名（未知 id 原样返回）。 */
    public static String displayName(String shape) {
        int i = indexOf(SHAPES, shape);
        return SHAPES[i].equals(shape) ? SHAPE_NAMES[i] : shape;
    }

    /** 在字符串数组里找值的位置，找不到返回 0。 */
    public static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    /** 设置形状（命令与按键共用），非法名字返回 false。 */
    public static boolean setShape(String shape) {
        for (String s : SHAPES) {
            if (s.equals(shape)) {
                shapeOverride = s;
                return true;
            }
        }
        return false;
    }

    /** 切换到下一个/上一个形状。 */
    public static void cycleShape(int dir) {
        int n = SHAPES.length;
        shapeOverride = SHAPES[((index() + dir) % n + n) % n];
    }
}
