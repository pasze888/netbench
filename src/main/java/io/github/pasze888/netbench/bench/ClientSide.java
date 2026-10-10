// SPDX-License-Identifier: LGPL-3.0-only
package io.github.pasze888.netbench.bench;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.pasze888.netbench.NetBench;
import io.github.pasze888.netbench.net.Payloads;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端侧的"回声"逻辑。
 *
 * 注意：这里是**通用代码**（不 import 任何 net.minecraft.client.*），所以 dedicated server 加载这个类也没问题，
 * 这些 handler 在服务端永远不会被调用。客户端不需要 tick 钩子：所有主动发送都是对服务端消息的 reply。
 */
public final class ClientSide {
    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private ClientSide() {
    }

    static final class State {
        final BenchParams params;
        final byte[] filler;
        final AtomicInteger seq = new AtomicInteger();
        final AtomicLong recvPackets = new AtomicLong();
        final AtomicLong recvBytes = new AtomicLong();

        State(BenchParams params) {
            this.params = params;
            this.filler = BenchSession.buildFiller(params.payloadSize(), params.pattern());
        }
    }

    public static void onControl(Payloads.ControlPayload payload, IPayloadContext context) {
        switch (payload.action()) {
            case Payloads.CTRL_START -> {
                BenchParams params = new BenchParams(payload.durationMs(), payload.payloadSize(),
                        payload.packetsPerTick(),
                        BenchParams.Direction.values()[payload.direction()],
                        BenchParams.Pattern.values()[payload.pattern()],
                        payload.intervalMs());
                STATES.put(payload.sessionId(), new State(params));
                NetBench.LOGGER.info("netbench: session {} start, size={} dir={}", payload.sessionId(),
                        params.payloadSize(), params.direction());
                context.reply(new Payloads.ControlPayload(payload.sessionId(), Payloads.CTRL_READY,
                        payload.durationMs(), payload.payloadSize(), payload.packetsPerTick(),
                        payload.direction(), payload.pattern(), payload.intervalMs()));
            }
            case Payloads.CTRL_STOP -> {
                State state = STATES.remove(payload.sessionId());
                long packets = state == null ? 0 : state.recvPackets.get();
                long bytes = state == null ? 0 : state.recvBytes.get();
                context.reply(new Payloads.StatPayload(payload.sessionId(), Payloads.STAT_CLIENT_REPORT, packets, bytes, 0));
                NetBench.LOGGER.info("netbench: session {} stop, received {} pkt / {} B", payload.sessionId(), packets, bytes);
            }
            default -> {
            }
        }
    }

    /** 服务端要求回 count 个数据包（跑在 netty 线程上，直接计数/回包）。 */
    public static void onPoll(Payloads.PollPayload payload, IPayloadContext context) {
        State state = STATES.get(payload.sessionId());
        if (state == null) return;
        int count = Math.min(payload.count(), NetBench.MAX_PACKETS_PER_POLL);
        for (int i = 0; i < count; i++) {
            context.reply(new Payloads.DataPayload(payload.sessionId(), state.seq.getAndIncrement(), state.filler));
        }
    }

    public static void onData(Payloads.DataPayload payload) {
        State state = STATES.get(payload.sessionId());
        if (state == null) return;
        state.recvPackets.incrementAndGet();
        state.recvBytes.addAndGet(payload.data().length);
    }

    public static void onStat(Payloads.StatPayload payload, IPayloadContext context) {
        if (payload.kind() == Payloads.STAT_PING) {
            context.reply(new Payloads.StatPayload(payload.sessionId(), Payloads.STAT_PONG, payload.a(), payload.b(), 0));
        }
    }
}
