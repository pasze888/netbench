package io.github.pasze888.netbench;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * NetBench —— Minecraft 网络性能基准测试（iperf3 for Minecraft）。
 *
 * 设计要点：测试完全由服务端驱动，客户端只做"回声"：
 *   - 服务端 /netbench run ... 建立会话，按 tick 向客户端发 Poll(要求 N 个包) 或直接发数据包；
 *   - 客户端在通用 payload handler 里用 {@code context.reply(...)} 回包，因此不需要任何客户端专属代码，
 *     天然支持 dedicated server 与单机集成服务器。
 *   - 服务端每 100ms~1s 采样一次，输出 iperf3 风格的 interval 行与汇总表。
 *
 * 参考（未复制代码）：
 *   - KryptonHybrid (dreamforSh/KryptonHybrid, LGPL-3.0) 的 NetworkTrafficStats / StatsSnapshotPayload /
 *     /krypton stats 命令，用来借鉴"自定义 payload + 命令 + 统计快照"的组织方式。
 *   - 工程骨架来自 NeoForge MDK 26.1.2 (MDK-26.1.2-ModDevGradle)。
 */
@Mod(NetBench.MODID)
public class NetBench {
    public static final String MODID = "netbench";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 单个数据 payload 的数据部分上限（字节）。 */
    public static final int MAX_PAYLOAD_BYTES = 1 << 20; // 1 MiB
    /** 每次 Poll 允许客户端回包数上限，防止误用打爆内存。 */
    public static final int MAX_PACKETS_PER_POLL = 4096;

    public NetBench(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("NetBench loaded: /netbench help");
    }
}
