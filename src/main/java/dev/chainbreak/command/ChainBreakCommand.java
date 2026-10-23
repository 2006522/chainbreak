package dev.chainbreak.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.chainbreak.ChainBreakConfig;
import dev.chainbreak.ChainBreakState;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * 游戏内配置命令 —— 对应 FTB Library 的"配置界面"能力，这里用命令实现。
 * <p>
 * /chainbreak                 查看当前设置
 * /chainbreak shape [next|prev|&lt;名称&gt;]   切换形状（所有人可用）
 * /chainbreak max &lt;1-1024&gt;     最大方块数
 * /chainbreak trigger &lt;key|sneak|both&gt;
 * /chainbreak cost exhaustion|xp &lt;值&gt;
 * /chainbreak cooldown &lt;刻&gt;
 * /chainbreak blacklist add|remove|clear [方块id]
 * /chainbreak reload          重新读取配置文件
 */
public final class ChainBreakCommand {

    private ChainBreakCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(Commands.literal("chainbreak")
                        .executes(ctx -> info(ctx.getSource()))
                        .then(Commands.literal("info")
                                .executes(ctx -> info(ctx.getSource())))
                        .then(Commands.literal("reload")
                                .requires(ChainBreakCommand::isAdmin)
                                .executes(ctx -> {
                                    ChainBreakConfig.reload();
                                    ChainBreakState.shapeOverride = null;
                                    return ok(ctx.getSource(), "配置已重新加载");
                                }))
                        .then(Commands.literal("shape")
                                .executes(ctx -> shapeInfo(ctx.getSource()))
                                .then(Commands.literal("next").executes(ctx -> {
                                    ChainBreakState.cycleShape(1);
                                    return shapeInfo(ctx.getSource());
                                }))
                                .then(Commands.literal("prev").executes(ctx -> {
                                    ChainBreakState.cycleShape(-1);
                                    return shapeInfo(ctx.getSource());
                                }))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                                            if (!ChainBreakState.setShape(name)) {
                                                return fail(ctx.getSource(), "未知形状：" + name
                                                        + "（可选 shapeless / tunnel_1x1 / tunnel_3x3 / square_3x3 / stair_up / stair_down）");
                                            }
                                            return shapeInfo(ctx.getSource());
                                        })))
                        .then(Commands.literal("max")
                                .requires(ChainBreakCommand::isAdmin)
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 1024))
                                        .executes(ctx -> {
                                            ChainBreakConfig.maxBlocks = IntegerArgumentType.getInteger(ctx, "value");
                                            ChainBreakConfig.save();
                                            return ok(ctx.getSource(), "最大方块数 = " + ChainBreakConfig.maxBlocks);
                                        })))
                        .then(Commands.literal("trigger")
                                .requires(ChainBreakCommand::isAdmin)
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
                                            if (!mode.equals("key") && !mode.equals("sneak") && !mode.equals("both")) {
                                                return fail(ctx.getSource(), "只能是 key / sneak / both");
                                            }
                                            ChainBreakConfig.trigger = mode;
                                            ChainBreakConfig.save();
                                            return ok(ctx.getSource(), "触发方式 = " + mode);
                                        })))
                        .then(Commands.literal("cost")
                                .requires(ChainBreakCommand::isAdmin)
                                .then(Commands.literal("exhaustion")
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                .executes(ctx -> {
                                                    ChainBreakConfig.exhaustionPerBlock =
                                                            DoubleArgumentType.getDouble(ctx, "value");
                                                    ChainBreakConfig.save();
                                                    return ok(ctx.getSource(), "每方块饥饿消耗 = "
                                                            + ChainBreakConfig.exhaustionPerBlock);
                                                })))
                                .then(Commands.literal("xp")
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                .executes(ctx -> {
                                                    ChainBreakConfig.experiencePerBlock =
                                                            DoubleArgumentType.getDouble(ctx, "value");
                                                    ChainBreakConfig.save();
                                                    return ok(ctx.getSource(), "每方块经验消耗 = "
                                                            + ChainBreakConfig.experiencePerBlock);
                                                }))))
                        .then(Commands.literal("cooldown")
                                .requires(ChainBreakCommand::isAdmin)
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 72000))
                                        .executes(ctx -> {
                                            ChainBreakConfig.cooldownTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                                            ChainBreakConfig.save();
                                            return ok(ctx.getSource(), "冷却 = " + ChainBreakConfig.cooldownTicks + " 刻");
                                        })))
                        .then(Commands.literal("blacklist")
                                .requires(ChainBreakCommand::isAdmin)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("block", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String id = StringArgumentType.getString(ctx, "block").toLowerCase();
                                                    ChainBreakConfig.blacklist.add(id);
                                                    ChainBreakConfig.save();
                                                    return ok(ctx.getSource(), "已加入黑名单：" + id);
                                                })))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("block", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String id = StringArgumentType.getString(ctx, "block").toLowerCase();
                                                    boolean removed = ChainBreakConfig.blacklist.remove(id);
                                                    ChainBreakConfig.save();
                                                    return removed
                                                            ? ok(ctx.getSource(), "已移出黑名单：" + id)
                                                            : fail(ctx.getSource(), "黑名单里没有 " + id);
                                                })))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> {
                                            ChainBreakConfig.blacklist.clear();
                                            ChainBreakConfig.save();
                                            return ok(ctx.getSource(), "黑名单已清空");
                                        })))
                ));
    }

    /** 命令权限：等同原版 2 级（gamemaster）。 */
    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static int info(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "§6[连锁破坏] §f形状=" + ChainBreakState.effectiveShapeName()
                        + " §7| §f上限=" + ChainBreakConfig.maxBlocks
                        + " §7| §f触发=" + ChainBreakConfig.trigger
                        + " §7| §f饥饿=" + ChainBreakConfig.exhaustionPerBlock
                        + " §7| §f经验=" + ChainBreakConfig.experiencePerBlock
                        + " §7| §f冷却=" + ChainBreakConfig.cooldownTicks + "刻"
                        + " §7| §f黑名单=" + ChainBreakConfig.blacklist.size() + "项"), false);
        return 1;
    }

    private static int shapeInfo(CommandSourceStack source) {
        return ok(source, "当前形状：" + ChainBreakState.effectiveShapeName()
                + "（" + ChainBreakState.effectiveShape() + "）");
    }

    private static int ok(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal("§a[连锁破坏] §f" + msg), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String msg) {
        source.sendFailure(Component.literal("§c[连锁破坏] " + msg));
        return 0;
    }
}
