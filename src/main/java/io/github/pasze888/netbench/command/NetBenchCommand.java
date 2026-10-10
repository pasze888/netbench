// SPDX-License-Identifier: LGPL-3.0-only
package io.github.pasze888.netbench.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import io.github.pasze888.netbench.NetBench;
import io.github.pasze888.netbench.bench.BenchParams;
import io.github.pasze888.netbench.bench.LocalBench;
import io.github.pasze888.netbench.bench.ServerBench;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /netbench - iperf3 style commands.
 *
 *   /netbench run &lt;seconds&gt; &lt;size&gt; [direction] [rate] [pattern]
 *   /netbench matrix &lt;seconds&gt; &lt;sizes,csv&gt; [direction] [rate] [pattern]
 *   /netbench local [seconds] [sizes,csv]
 *   /netbench status | stop | help
 */
@EventBusSubscriber(modid = NetBench.MODID)
public final class NetBenchCommand {
    private static final SuggestionProvider<CommandSourceStack> DIRECTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(new String[] {"c2s", "s2c", "both"}, builder);
    private static final SuggestionProvider<CommandSourceStack> PATTERNS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(new String[] {"zero", "random", "counter"}, builder);
    private static final SuggestionProvider<CommandSourceStack> SIZES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    new String[] {"64", "256", "1400", "8192", "65536", "64,256,1400,8192,65536"}, builder);

    private NetBenchCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("netbench")
                .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("help").executes(NetBenchCommand::help))
                .then(Commands.literal("status").executes(ctx -> {
                    msg(ctx, ServerBench.status());
                    return 1;
                }))
                .then(Commands.literal("stop").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    msg(ctx, ServerBench.stop(player, "command") ? "stopped" : "no active test");
                    return 1;
                }))
                .then(Commands.literal("run")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .then(Commands.argument("size", IntegerArgumentType.integer(1, NetBench.MAX_PAYLOAD_BYTES))
                                        .executes(ctx -> run(ctx, "c2s", 64, "zero"))
                                        .then(Commands.argument("direction", StringArgumentType.word())
                                                .suggests(DIRECTIONS)
                                                .executes(ctx -> run(ctx, str(ctx, "direction"), 64, "zero"))
                                                .then(Commands.argument("rate", IntegerArgumentType.integer(1, NetBench.MAX_PACKETS_PER_POLL))
                                                        .executes(ctx -> run(ctx, str(ctx, "direction"), intArg(ctx, "rate"), "zero"))
                                                        .then(Commands.argument("pattern", StringArgumentType.word())
                                                                .suggests(PATTERNS)
                                                                .executes(ctx -> run(ctx, str(ctx, "direction"), intArg(ctx, "rate"), str(ctx, "pattern")))))))))
                .then(Commands.literal("matrix")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .then(Commands.argument("sizes", StringArgumentType.string())
                                        .suggests(SIZES)
                                        .executes(ctx -> matrix(ctx, "c2s", 64, "zero"))
                                        .then(Commands.argument("direction", StringArgumentType.word())
                                                .suggests(DIRECTIONS)
                                                .executes(ctx -> matrix(ctx, str(ctx, "direction"), 64, "zero"))
                                                .then(Commands.argument("rate", IntegerArgumentType.integer(1, NetBench.MAX_PACKETS_PER_POLL))
                                                        .executes(ctx -> matrix(ctx, str(ctx, "direction"), intArg(ctx, "rate"), "zero"))
                                                        .then(Commands.argument("pattern", StringArgumentType.word())
                                                                .suggests(PATTERNS)
                                                                .executes(ctx -> matrix(ctx, str(ctx, "direction"), intArg(ctx, "rate"), str(ctx, "pattern")))))))))
                .then(Commands.literal("local")
                        .executes(ctx -> local(ctx, 2, null))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes(ctx -> local(ctx, intArg(ctx, "seconds"), null))
                                .then(Commands.argument("sizes", StringArgumentType.string())
                                        .suggests(SIZES)
                                        .executes(ctx -> local(ctx, intArg(ctx, "seconds"), str(ctx, "sizes")))))));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        msg(ctx, "NetBench - Minecraft network benchmark (iperf3 style)");
        msg(ctx, "/netbench run <seconds> <size> [c2s|s2c|both] [pkt/tick] [zero|random|counter]");
        msg(ctx, "/netbench matrix <seconds> <sizes,csv> [dir] [pkt/tick] [pattern]   e.g. matrix 5 32,128,512,1400,8192");
        msg(ctx, "/netbench local [seconds] [sizes,csv]   local MC pipeline bench (CFB8 vs CTR, +-zlib)");
        msg(ctx, "/netbench status | stop");
        msg(ctx, "note: the client side must also have NetBench installed; RTT is sampled once per second.");
        return 1;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String direction, int rate, String pattern) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BenchParams params = new BenchParams(
                intArg(ctx, "seconds") * 1000,
                intArg(ctx, "size"),
                rate,
                BenchParams.Direction.parse(direction),
                BenchParams.Pattern.parse(pattern),
                1000);
        ServerBench.start(player, params);
        return 1;
    }

    private static int matrix(CommandContext<CommandSourceStack> ctx, String direction, int rate, String pattern) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int seconds = intArg(ctx, "seconds");
        int[] sizes = LocalBench.parseSizes(str(ctx, "sizes"));
        BenchParams.Direction dir = BenchParams.Direction.parse(direction);
        BenchParams.Pattern pat = BenchParams.Pattern.parse(pattern);
        List<BenchParams> list = new ArrayList<>();
        for (int size : sizes) {
            list.add(new BenchParams(seconds * 1000, size, rate, dir, pat, 1000));
        }
        ServerBench.queue(player, list);
        return 1;
    }

    private static int local(CommandContext<CommandSourceStack> ctx, int seconds, String sizesCsv) {
        int[] sizes = sizesCsv == null ? LocalBench.DEFAULT_SIZES : LocalBench.parseSizes(sizesCsv);
        CommandSourceStack source = ctx.getSource();
        msg(ctx, "local pipeline benchmark: " + seconds + "s per case, sizes=" + java.util.Arrays.toString(sizes));
        Thread thread = new Thread(() -> {
            String table = LocalBench.format(LocalBench.run(sizes, seconds));
            source.getServer().execute(() -> {
                for (String line : table.split("\n")) {
                    if (!line.isBlank()) source.sendSystemMessage(Component.literal(line));
                }
            });
        }, "netbench-local");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int intArg(CommandContext<CommandSourceStack> ctx, String name) {
        return IntegerArgumentType.getInteger(ctx, name);
    }

    private static String str(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static void msg(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSystemMessage(Component.literal("[netbench] " + text));
    }
}
