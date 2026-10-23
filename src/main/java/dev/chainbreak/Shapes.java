package dev.chainbreak;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 几何形状生成，语义对齐 FTB Ultimine 的六个形状。
 */
public final class Shapes {

    public static final String SHAPELESS = "shapeless";

    private Shapes() {
    }

    /**
     * 生成形状覆盖的方块坐标（不含起始方块，起始方块已被玩家挖掉）。
     *
     * @param face 挖掘朝向（水平朝向），通道沿此方向延伸
     */
    public static List<BlockPos> geometric(String shape, BlockPos origin, Direction face, int max) {
        List<BlockPos> out = new ArrayList<>(Math.min(max, 64));
        Direction right = face.getClockWise();

        switch (shape) {
            case "tunnel_1x1" -> {
                for (int d = 1; d <= max; d++) {
                    out.add(origin.relative(face, d));
                }
            }
            case "square_3x3" -> addLayer(out, origin, face, right, 0, 0, max);
            case "tunnel_3x3" -> {
                int depth = Math.max(1, max / 9);
                for (int d = 0; d < depth; d++) {
                    addLayer(out, origin, face, right, d, 0, max);
                }
            }
            case "stair_up" -> {
                int depth = Math.max(1, max / 9);
                for (int d = 0; d < depth; d++) {
                    addLayer(out, origin, face, right, d, d, max);
                }
            }
            case "stair_down" -> {
                int depth = Math.max(1, max / 9);
                for (int d = 0; d < depth; d++) {
                    addLayer(out, origin, face, right, d, -d, max);
                }
            }
            default -> {
                // 未知形状按无形状处理，由调用方走 BFS
            }
        }
        return out;
    }

    /** 在深度 depth 处铺一层 3x3 截面，step 为楼梯的垂直偏移系数。 */
    private static void addLayer(List<BlockPos> out, BlockPos origin, Direction face, Direction right,
                                 int depth, int step, int max) {
        BlockPos base = origin.relative(face, depth).relative(Direction.UP, step);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (out.size() >= max) {
                    return;
                }
                BlockPos p = base.relative(Direction.UP, dy).relative(right, dx);
                if (!p.equals(origin)) {
                    out.add(p);
                }
            }
        }
    }
}
