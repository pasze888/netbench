package io.github.pasze888.netbench.bench;

import io.github.pasze888.netbench.NetBench;

/** iperf3 风格的一次测试参数。 */
public record BenchParams(
        int durationMs,
        int payloadSize,
        int packetsPerTick,
        Direction direction,
        Pattern pattern,
        int intervalMs
) {
    public enum Direction {
        /** 客户端 -> 服务端（iperf3 默认方向） */
        C2S,
        /** 服务端 -> 客户端（iperf3 -R） */
        S2C,
        /** 双向同时 */
        BOTH;

        public boolean c2s() { return this == C2S || this == BOTH; }
        public boolean s2c() { return this == S2C || this == BOTH; }

        public static Direction parse(String s) {
            return switch (s.toLowerCase()) {
                case "c2s", "up", "upload" -> C2S;
                case "s2c", "down", "download" -> S2C;
                case "both", "duplex" -> BOTH;
                default -> throw new IllegalArgumentException("direction must be c2s|s2c|both");
            };
        }
    }

    /** 载荷内容，用来对比压缩打开/关闭时的差异。 */
    public enum Pattern {
        /** 全 0，极度可压缩 */
        ZERO,
        /** 伪随机，不可压缩 */
        RANDOM,
        /** 递增计数器，半可压缩 */
        COUNTER;

        public static Pattern parse(String s) {
            return switch (s.toLowerCase()) {
                case "zero", "zeros" -> ZERO;
                case "random", "rand" -> RANDOM;
                case "counter" -> COUNTER;
                default -> throw new IllegalArgumentException("pattern must be zero|random|counter");
            };
        }
    }

    public BenchParams {
        if (durationMs <= 0) throw new IllegalArgumentException("duration must be > 0");
        if (payloadSize <= 0 || payloadSize > NetBench.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("size must be 1.." + NetBench.MAX_PAYLOAD_BYTES);
        if (packetsPerTick <= 0 || packetsPerTick > NetBench.MAX_PACKETS_PER_POLL)
            throw new IllegalArgumentException("rate must be 1.." + NetBench.MAX_PACKETS_PER_POLL);
        if (intervalMs < 100) intervalMs = 100;
    }

    public BenchParams withSize(int size) {
        return new BenchParams(durationMs, size, packetsPerTick, direction, pattern, intervalMs);
    }
}
