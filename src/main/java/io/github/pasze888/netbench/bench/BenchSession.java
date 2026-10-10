// SPDX-License-Identifier: LGPL-3.0-only
package io.github.pasze888.netbench.bench;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import io.github.pasze888.netbench.NetBench;
import io.github.pasze888.netbench.net.Payloads;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** 一次基准测试的会话状态（服务端持有）。 */
public final class BenchSession {
    public final int id;
    public final ServerPlayer player;
    public final BenchParams params;

    final long startNanos;
    long lastSampleNanos;

    // 每 interval 的增量基准（只在本 tick 线程里读写）
    long markPackets;
    long markBytes;
    long markS2cPackets;
    long markS2cBytes;

    // 累计计数（网络线程 ++，tick 线程读）
    final AtomicLong c2sPackets = new AtomicLong();
    final AtomicLong c2sBytes = new AtomicLong();
    final AtomicLong s2cPackets = new AtomicLong();
    final java.util.concurrent.atomic.AtomicInteger pollReceived = new java.util.concurrent.atomic.AtomicInteger();
    final AtomicLong s2cBytes = new AtomicLong();

    // poll -> 回包完成的延迟
    long pollSentAtNanos = -1L;
    int pollOutstanding;
    long[] pollLatency = new long[4096];
    int pollLatencyCount;

    // RTT（服务端发 PING，客户端回 PONG）
    long pingNonce;
    long pingSentAt;
    long lastPingAt;
    long[] rtts = new long[4096];
    int rttCount;

    // 客户端回报（S2C 交叉校验）
    volatile long clientRecvPackets = -1;
    volatile long clientRecvBytes = -1;

    volatile boolean live = true;
    volatile boolean stopSent;

    final byte[] filler;

    public BenchSession(int id, ServerPlayer player, BenchParams params) {
        this.id = id;
        this.player = player;
        this.params = params;
        this.startNanos = System.nanoTime();
        this.lastSampleNanos = startNanos;
        this.filler = buildFiller(params.payloadSize(), params.pattern());
    }

    static byte[] buildFiller(int size, BenchParams.Pattern pattern) {
        byte[] data = new byte[size];
        switch (pattern) {
            case ZERO -> {
            }
            case RANDOM -> new java.util.Random(1234567L).nextBytes(data);
            case COUNTER -> {
                for (int i = 0; i < size; i++) data[i] = (byte) i;
            }
        }
        return data;
    }

    public boolean expired(long nowNanos) {
        return (nowNanos - startNanos) / 1_000_000L >= params.durationMs();
    }

    // ---------- 由服务端 tick 调用 ----------

    void sendS2c() {
        int n = params.packetsPerTick();
        for (int i = 0; i < n; i++) {
            int seq = (int) s2cPackets.incrementAndGet();
            PacketDistributor.sendToPlayer(player, new Payloads.DataPayload(id, seq, filler));
            s2cBytes.addAndGet(filler.length);
        }
    }

    void sendPoll() {
        pollSentAtNanos = System.nanoTime();
        pollOutstanding = params.packetsPerTick();
        PacketDistributor.sendToPlayer(player, new Payloads.PollPayload(id, params.packetsPerTick()));
    }

    void maybePing(long now) {
        if (now - lastPingAt < 1_000_000_000L) return;
        lastPingAt = now;
        pingNonce++;
        pingSentAt = now;
        PacketDistributor.sendToPlayer(player, new Payloads.StatPayload(id, Payloads.STAT_PING, pingNonce, now, 0));
    }

    void onPollComplete() {
        if (pollSentAtNanos > 0 && pollLatencyCount < pollLatency.length) {
            pollLatency[pollLatencyCount++] = System.nanoTime() - pollSentAtNanos;
            pollSentAtNanos = -1;
        }
    }

    void onPong(long nonceIgnored) {
        if (rttCount < rtts.length) rtts[rttCount++] = System.nanoTime() - pingSentAt;
    }

    String sample(long now) {
        long dNanos = now - lastSampleNanos;
        long dPackets = (c2sPackets.get() - markPackets) + (s2cPackets.get() - markS2cPackets);
        long dBytes = (c2sBytes.get() - markBytes) + (s2cBytes.get() - markS2cBytes);
        markPackets = c2sPackets.get() + s2cPackets.get();
        markBytes = c2sBytes.get() + s2cBytes.get();
        markS2cPackets = s2cPackets.get();
        markS2cBytes = s2cBytes.get();
        lastSampleNanos = now;

        double secs = dNanos / 1e9;
        double mbps = dBytes * 8.0 / 1e6 / secs;
        double pktPerSec = dPackets / secs;
        long rttUs = rttCount == 0 ? 0 : rtts[rttCount - 1] / 1000;
        return String.format("[%3d] %5.1fs  %9s  %8.2f Mbits/sec  %7.0f pkt/s  rtt %5.2f ms",
                id, (now - startNanos) / 1e9, human(dBytes), mbps, pktPerSec, rttUs / 1000.0);
    }

    String summary(long now) {
        long totalPackets = c2sPackets.get() + s2cPackets.get();
        long totalBytes = c2sBytes.get() + s2cBytes.get();
        double secs = Math.max(1e-9, (now - startNanos) / 1e9);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("- - - - - - - - - - - - - - - - - - - - - - - - - -%n"));
        sb.append(String.format("[%3d] summary  size=%d B  dir=%s  pattern=%s  rate=%d pkt/tick%n",
                id, params.payloadSize(), params.direction(), params.pattern(), params.packetsPerTick()));
        sb.append(String.format("      duration      %.2f s%n", secs));
        sb.append(String.format("      C2S  transfer %s  %,d pkt  %.2f Mbits/sec%n", human(c2sBytes.get()), c2sPackets.get(),
                c2sBytes.get() * 8.0 / 1e6 / secs));
        sb.append(String.format("      S2C  transfer %s  %,d pkt  %.2f Mbits/sec%n", human(s2cBytes.get()), s2cPackets.get(),
                s2cBytes.get() * 8.0 / 1e6 / secs));
        sb.append(String.format("      total %s  %,d pkt  %.2f Mbits/sec%n", human(totalBytes), totalPackets,
                totalBytes * 8.0 / 1e6 / secs));
        if (rttCount > 0) {
            long[] r = Arrays.copyOf(rtts, rttCount);
            Arrays.sort(r);
            sb.append(String.format("      RTT  avg %.2f ms  p50 %.2f ms  p99 %.2f ms  max %.2f ms  (n=%d)%n",
                    avg(r) / 1e6, r[r.length / 2] / 1e6, r[(int) (r.length * 0.99)] / 1e6, r[r.length - 1] / 1e6, r.length));
        }
        if (pollLatencyCount > 0) {
            long[] p = Arrays.copyOf(pollLatency, pollLatencyCount);
            Arrays.sort(p);
            sb.append(String.format("      poll->last-packet  avg %.2f ms  p50 %.2f ms  p99 %.2f ms  (n=%d)%n",
                    avg(p) / 1e6, p[p.length / 2] / 1e6, p[(int) (p.length * 0.99)] / 1e6, p.length));
        }
        if (clientRecvPackets >= 0) {
            sb.append(String.format("      client reported: %,d pkt / %s received (S2C cross-check)%n",
                    clientRecvPackets, human(clientRecvBytes)));
        }
        return sb.toString();
    }

    static long avg(long[] v) {
        long s = 0;
        for (long x : v) s += x;
        return v.length == 0 ? 0 : s / v.length;
    }

    public static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format("%.2f GiB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format("%.2f MiB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return String.format("%.2f KiB", bytes / (double) (1L << 10));
        return bytes + " B";
    }

    void tell(String msg) {
        player.sendSystemMessage(Component.literal("[netbench] " + msg));
        NetBench.LOGGER.info("[netbench] {}", msg);
    }
}
