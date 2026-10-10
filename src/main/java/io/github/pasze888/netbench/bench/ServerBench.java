// SPDX-License-Identifier: LGPL-3.0-only
package io.github.pasze888.netbench.bench;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.pasze888.netbench.NetBench;
import io.github.pasze888.netbench.net.Payloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端：会话注册表、tick 驱动、payload handler。 */
@EventBusSubscriber(modid = NetBench.MODID)
public final class ServerBench {
    private static final Map<Integer, BenchSession> BY_ID = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> BY_PLAYER = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<UUID, Deque<BenchParams>> QUEUE = new ConcurrentHashMap<>();

    private ServerBench() {
    }

    // ---------- 对外 API（命令用） ----------

    public static BenchSession start(ServerPlayer player, BenchParams params) {
        stop(player, "superseded by a new run");
        BenchSession session = new BenchSession(NEXT_ID.incrementAndGet(), player, params);
        BY_ID.put(session.id, session);
        BY_PLAYER.put(player.getUUID(), session.id);
        session.tell(String.format("start: size=%d B dir=%s pattern=%s rate=%d pkt/tick duration=%ds",
                params.payloadSize(), params.direction(), params.pattern(), params.packetsPerTick(),
                params.durationMs() / 1000));
        PacketDistributor.sendToPlayer(player, controlFor(session, Payloads.CTRL_START));
        return session;
    }

    public static boolean stop(ServerPlayer player, String reason) {
        Integer id = BY_PLAYER.get(player.getUUID());
        if (id == null) return false;
        BenchSession s = BY_ID.remove(id);
        BY_PLAYER.remove(player.getUUID());
        if (s != null) {
            PacketDistributor.sendToPlayer(player, controlFor(s, Payloads.CTRL_STOP));
            s.tell("stopped: " + reason);
        }
        return true;
    }

    public static boolean stop(int sessionId, String reason) {
        BenchSession s = BY_ID.remove(sessionId);
        if (s == null) return false;
        BY_PLAYER.remove(s.player.getUUID());
        PacketDistributor.sendToPlayer(s.player, controlFor(s, Payloads.CTRL_STOP));
        s.tell("stopped: " + reason);
        return true;
    }

    /** matrix：把多个包长的测试排队依次跑，每个结束后打印汇总。 */
    public static void queue(ServerPlayer player, List<BenchParams> params) {
        stop(player, "matrix run");
        QUEUE.put(player.getUUID(), new ArrayDeque<>(params));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[netbench] matrix queued: " + params.size() + " cases"));
        startNext(player);
    }

    private static void startNext(ServerPlayer player) {
        Deque<BenchParams> queue = QUEUE.get(player.getUUID());
        if (queue == null || queue.isEmpty()) {
            QUEUE.remove(player.getUUID());
            return;
        }
        start(player, queue.poll());
    }

    public static String status() {
        if (BY_ID.isEmpty()) return "no active test";
        StringBuilder sb = new StringBuilder();
        for (BenchSession s : BY_ID.values()) {
            sb.append(String.format("session %d: player=%s size=%d dir=%s c2s=%d pkt/%s s2c=%d pkt/%s%n",
                    s.id, s.player.getScoreboardName(), s.params.payloadSize(), s.params.direction(),
                    s.c2sPackets.get(), BenchSession.human(s.c2sBytes.get()),
                    s.s2cPackets.get(), BenchSession.human(s.s2cBytes.get())));
        }
        return sb.toString().trim();
    }

    static Payloads.ControlPayload controlFor(BenchSession s, byte action) {
        return new Payloads.ControlPayload(s.id, action, s.params.durationMs(), s.params.payloadSize(),
                s.params.packetsPerTick(), (byte) s.params.direction().ordinal(),
                (byte) s.params.pattern().ordinal(), s.params.intervalMs());
    }

    // ---------- tick 驱动 ----------

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (BY_ID.isEmpty()) return;
        long now = System.nanoTime();
        for (BenchSession s : new ArrayList<>(BY_ID.values())) {
            if (s.player.hasDisconnected()) {
                finish(s, now, "player disconnected");
                continue;
            }
            if (s.expired(now)) {
                finish(s, now, "duration reached");
                continue;
            }
            if (s.params.direction().c2s()) s.sendPoll();
            if (s.params.direction().s2c()) s.sendS2c();
            s.maybePing(now);
            if (now - s.lastSampleNanos >= s.params.intervalMs() * 1_000_000L) {
                s.tell(s.sample(now));
            }
        }
    }

    private static void finish(BenchSession s, long now, String reason) {
        BY_ID.remove(s.id);
        BY_PLAYER.remove(s.player.getUUID());
        PacketDistributor.sendToPlayer(s.player, controlFor(s, Payloads.CTRL_STOP));
        s.tell("done (" + reason + ")");
        for (String line : s.summary(now).split("\n")) {
            if (!line.isBlank()) s.tell(line);
        }
        startNext(s.player);
    }

    // ---------- payload handlers（服务端收到的） ----------

    public static void onControl(Payloads.ControlPayload payload, IPayloadContext context) {
        if (payload.action() != Payloads.CTRL_READY) return;
        BenchSession s = BY_ID.get(payload.sessionId());
        if (s == null) return;
        NetBench.LOGGER.debug("client ready for session {}", s.id);
    }

    public static void onPoll(Payloads.PollPayload payload) {
        // 服务端不会收到 Poll
    }

    /** 在 netty 线程直接计数：这是热路径，不做 enqueueWork。 */
    public static void onData(Payloads.DataPayload payload) {
        BenchSession s = BY_ID.get(payload.sessionId());
        if (s == null) return;
        s.c2sPackets.incrementAndGet();
        s.c2sBytes.addAndGet(payload.data().length);
        if (s.pollReceived.incrementAndGet() >= s.params.packetsPerTick()) {
            s.pollReceived.addAndGet(-s.params.packetsPerTick());
            s.onPollComplete();
        }
    }

    public static void onStat(Payloads.StatPayload payload, IPayloadContext context) {
        BenchSession s = BY_ID.get(payload.sessionId());
        if (s == null) return;
        switch (payload.kind()) {
            case Payloads.STAT_PONG -> s.onPong(payload.a());
            case Payloads.STAT_CLIENT_REPORT -> {
                s.clientRecvPackets = payload.a();
                s.clientRecvBytes = payload.b();
                s.tell(String.format("client report: %,d pkt / %s received",
                        payload.a(), BenchSession.human(payload.b())));
            }
            default -> {
            }
        }
    }

    public static List<BenchSession> active() {
        return new ArrayList<>(BY_ID.values());
    }
}
