// SPDX-License-Identifier: LGPL-3.0-only
package io.github.pasze888.netbench.tools;

import java.util.List;

import io.github.pasze888.netbench.bench.LocalBench;

/** 无头入口：./gradlew localBench */
public final class LocalBenchMain {
    public static void main(String[] args) {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int[] sizes = args.length > 1 ? LocalBench.parseSizes(args[1]) : LocalBench.DEFAULT_SIZES;
        System.out.println("NetBench local pipeline benchmark (Minecraft CipherEncoder/CompressionEncoder + Netty EmbeddedChannel)");
        System.out.println("java " + System.getProperty("java.version") + ", " + Runtime.getRuntime().availableProcessors() + " cores");
        List<LocalBench.Result> results = LocalBench.run(sizes, seconds);
        System.out.print(LocalBench.format(results));
    }
}
