// SPDX-License-Identifier: LGPL-3.0-only
package io.github.pasze888.netbench.bench;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.CompressionEncoder;

/**
 * 不经过网络、也不启动游戏：直接把数据送进 Minecraft 真实的 pipeline 组件
 * （{@link CipherEncoder} / {@link CompressionEncoder}，Netty EmbeddedChannel），
 * 测量"每包开销 + 吞吐"，用来对比小包/大包以及 AES/CFB8 与 AES/CTR。
 */
public final class LocalBench {
    public record Result(String mode, int size, double mbps, double usPerPacket, double packetsPerSec, long packets) {
    }

    private static final byte[] KEY = new byte[16];
    static {
        new SecureRandom(new byte[] {1, 2, 3, 4}).nextBytes(KEY);
    }

    private LocalBench() {
    }

    public static List<Result> run(int[] sizes, int secondsPerCase) {
        List<Result> out = new ArrayList<>();
        for (int size : sizes) {
            for (String mode : new String[] {"CFB8", "CTR", "CFB8+zlib", "CTR+zlib"}) {
                out.add(measure(mode, size, secondsPerCase));
            }
        }
        return out;
    }

    static Cipher cipher(String mode) {
        boolean ctr = mode.startsWith("CTR");
        try {
            if (ctr) {
                Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(KEY));
                return c;
            }
            Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(KEY));
            return c;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Result measure(String mode, int size, int seconds) {
        boolean zlib = mode.endsWith("+zlib");
        Cipher cipher = cipher(mode);
        EmbeddedChannel channel = zlib
                ? new EmbeddedChannel(new CompressionEncoder(256), new CipherEncoder(cipher))
                : new EmbeddedChannel(new CipherEncoder(cipher));

        ByteBuf source = Unpooled.buffer(size);
        source.writeZero(size);

        // 预热
        runFor(channel, source, size, 300_000_000L);
        long packets = runFor(channel, source, size, seconds * 1_000_000_000L);

        double secs = seconds;
        double mbps = packets * (double) size * 8.0 / 1e6 / secs;
        double usPerPacket = secs * 1e6 / packets;
        double pps = packets / secs;

        channel.finishAndReleaseAll();
        source.release();
        return new Result(mode, size, mbps, usPerPacket, pps, packets);
    }

    private static long runFor(EmbeddedChannel channel, ByteBuf source, int size, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        long packets = 0;
        while (System.nanoTime() < deadline) {
            channel.writeOutbound(source.retainedDuplicate());
            Object o;
            while ((o = channel.readOutbound()) != null) {
                ((ByteBuf) o).release();
            }
            packets++;
        }
        return packets;
    }

    public static String format(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-12s %10s %12s %14s %14s%n", "pipeline", "size(B)", "Mbit/s", "us/packet", "packets/s"));
        sb.append("-".repeat(66)).append('\n');
        for (Result r : results) {
            sb.append(String.format("%-12s %10d %12.1f %14.3f %14.0f%n",
                    r.mode(), r.size(), r.mbps(), r.usPerPacket(), r.packetsPerSec()));
        }
        return sb.toString();
    }

    public static int[] parseSizes(String csv) {
        String[] parts = csv.split("[,\s]+");
        int[] sizes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) sizes[i] = Integer.parseInt(parts[i].trim());
        return sizes;
    }

    public static final int[] DEFAULT_SIZES = {64, 256, 1400, 8192, 65536};
}
