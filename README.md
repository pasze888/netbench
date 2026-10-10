# NetBench

**iperf3 for Minecraft** —— 在真实的 Minecraft 连接上测带宽、包速率、延迟与包长影响。

| | |
|---|---|
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.114 |
| Java | 25 |
| mod id | `netbench` |
| 包名 | `io.github.pasze888.netbench` |
| License | MIT |

> 客户端和服务端**都要装**。测试由服务端命令发起，客户端只负责回声（不需要客户端专属代码）。

## 命令（游戏内聊天框执行，需要 OP / 权限等级 2）

```
/netbench help
/netbench run <seconds> <size> [c2s|s2c|both] [pkt/tick] [zero|random|counter]
/netbench matrix <seconds> <sizes,csv> [dir] [pkt/tick] [pattern]
/netbench local [seconds] [sizes,csv]
/netbench status
/netbench stop
```

与 iperf3 的对应关系：

| iperf3 | NetBench |
|---|---|
| `iperf3 -c host -t 10` | `/netbench run 10 1400 c2s` |
| `-l 1400`（包长） | 第 2 个参数 `size`（字节） |
| `-R`（反向） | `s2c`；`both` = 双向同时 |
| 带宽 / 包速率 | `pkt/tick`（每 tick 发包数，20 tick/s） |
| `-i 1`（每秒一行） | 固定每秒一行 interval |
| `--get-server-output` | 汇总里带两侧计数（S2C 交叉校验） |
| `-u` / UDP | 不支持（MC 走 TCP，装了 QUIC mod 则走 QUIC） |
| `-P`（多流） | 不支持（单连接） |

例子：

```
/netbench run 10 1400 both 64             # 双向，1400B 包，每 tick 64 个，跑 10 秒
/netbench matrix 5 32,128,512,1400,8192   # 小包到大包扫描，每个包长 5 秒
/netbench run 30 65536 c2s 8 random      # 大包 + 不可压缩内容，持续 30 秒
/netbench local 2 64,256,1400,8192        # 本机 pipeline 测试，不经过网络
```

输出（iperf3 风格）：

```
[netbench] start: size=1400 B dir=C2S pattern=ZERO rate=64 pkt/tick duration=10s
[netbench] [  3]   1.0s    1.71 MiB    14.3 Mbits/sec     1220 pkt/s  rtt  1.83 ms
... 每秒一行 ...
[netbench] - - - - - - - - - - - - - - - - - - - - - - - - - -
[netbench] [  3] summary  size=1400 B  dir=C2S  pattern=ZERO  rate=64 pkt/tick
[netbench]       duration      10.02 s
[netbench]       C2S  transfer 17.1 MiB  12,800 pkt  14.31 Mbits/sec
[netbench]       S2C  transfer 0 B  0 pkt  0.00 Mbits/sec
[netbench]       total 17.1 MiB  12,800 pkt  14.31 Mbits/sec
[netbench]       RTT  avg 1.91 ms  p50 1.83 ms  p99 3.42 ms  max 5.10 ms  (n=10)
[netbench]       poll->last-packet  avg 2.44 ms  p50 2.31 ms  p99 6.02 ms  (n=200)
[netbench]       client reported: 12,800 pkt / 17.1 MiB received (S2C cross-check)
```

## 本地 pipeline 基准（不需要第二个客户端，也不启动游戏）

```bash
./gradlew localBench                       # 默认 2 秒每档，包长 64/256/1400/8192/65536
./gradlew localBench --args="3 64,1400,65536"
```

它把数据直接灌进 Minecraft 真实的 `CipherEncoder` / `CompressionEncoder`（Netty `EmbeddedChannel`），
所以测的是**含 ByteBuf 分配与拷贝的每包真实开销**，对比：

```
pipeline      size(B)       Mbit/s      us/packet      packets/s
------------------------------------------------------------------
CFB8               64          ...            ...            ...
CTR                64          ...            ...            ...
CFB8+zlib          64          ...            ...            ...
CTR+zlib           64          ...            ...            ...
```

`+zlib` 用 `CompressionEncoder(256)`（原版 `network-compression-threshold=256` 的行为），
可以直观看到小包被压缩反而更慢/更大的经典现象。

## 实现要点

- **服务端驱动**：run 命令在服务端建立会话；每 tick：
  - `c2s`：给客户端发 `PollPayload(n)`，客户端立刻用 `context.reply(...)` 回 n 个 `DataPayload`；
  - `s2c`：服务端直接向玩家发 n 个 `DataPayload`。
  这样**完全不需要客户端 tick 钩子或任何客户端专属类**，dedicated server 和单机集成服务器都能跑。
- 计数器用原子量在 **netty 线程**直接累加（热路径不做 `enqueueWork`），interval 采样与汇总在服务端 tick 线程做。
- RTT：服务端每秒发 `StatPayload(PING, nonce, nanoTime)`，客户端原样回 `PONG`，服务端统计 avg/p50/p99/max。
- 每个 payload 的数据部分上限 1 MiB，单次 Poll 回包数上限 4096（`NetBench.MAX_*`）。
- 会话在时长到期、玩家掉线或 stop 时结束并打印汇总；`matrix` 会把多个包长的用例排队依次跑。

## 来源与许可

- 工程骨架：**NeoForge MDK 26.1.2**（`MDK-26.1.2-ModDevGradle`，见 `TEMPLATE_LICENSE.txt`）。
- 设计参考：[KryptonHybrid](https://github.com/dreamforSh/KryptonHybrid)（作者 dreamforSh，**LGPL-3.0**）
  的自定义 payload + 命令 + 统计快照组织方式（`NetworkTrafficStats`、`StatsSnapshotPayload`、`/krypton stats`）。
  **本项目未复制其任何代码**，仅参考思路，因此本模组以 MIT 发布；若后续搬运其代码，请遵守 LGPL-3.0。
