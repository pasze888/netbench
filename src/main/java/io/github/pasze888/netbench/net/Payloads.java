package io.github.pasze888.netbench.net;

import io.github.pasze888.netbench.NetBench;
import io.github.pasze888.netbench.bench.ClientSide;
import io.github.pasze888.netbench.bench.ServerBench;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 所有自定义 payload。客户端 handler 放在 {@link ClientSide}（纯通用代码，不引用任何客户端专属类），
 * 所以 dedicated server 也能安全加载这份注册表。
 */
@EventBusSubscriber(modid = NetBench.MODID)
public final class Payloads {
    private Payloads() {
    }

    public static final byte CTRL_START = 0;
    public static final byte CTRL_STOP = 1;
    public static final byte CTRL_READY = 2;
    public static final byte CTRL_ABORT = 3;

    public static final byte STAT_PING = 0;
    public static final byte STAT_PONG = 1;
    public static final byte STAT_CLIENT_REPORT = 2;
    public static final byte STAT_INTERVAL_C2S = 3;
    public static final byte STAT_INTERVAL_S2C = 4;

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playBidirectional(ControlPayload.TYPE, ControlPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ServerBench.onControl(payload, context)),
                (payload, context) -> context.enqueueWork(() -> ClientSide.onControl(payload, context)));

        registrar.playBidirectional(PollPayload.TYPE, PollPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ServerBench.onPoll(payload)),
                (payload, context) -> ClientSide.onPoll(payload, context));

        registrar.playBidirectional(DataPayload.TYPE, DataPayload.STREAM_CODEC,
                (payload, context) -> ServerBench.onData(payload),
                (payload, context) -> ClientSide.onData(payload));

        registrar.playBidirectional(StatPayload.TYPE, StatPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ServerBench.onStat(payload, context)),
                (payload, context) -> ClientSide.onStat(payload, context));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NetBench.MODID, path);
    }

    /** 控制消息：START/STOP/READY/ABORT，同时携带完整的测试参数。 */
    public record ControlPayload(int sessionId, byte action, int durationMs, int payloadSize,
                                 int packetsPerTick, byte direction, byte pattern, int intervalMs)
            implements CustomPacketPayload {
        public static final Type<ControlPayload> TYPE = new Type<>(id("control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ControlPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.sessionId);
                    buf.writeByte(p.action);
                    buf.writeVarInt(p.durationMs);
                    buf.writeVarInt(p.payloadSize);
                    buf.writeVarInt(p.packetsPerTick);
                    buf.writeByte(p.direction);
                    buf.writeByte(p.pattern);
                    buf.writeVarInt(p.intervalMs);
                },
                buf -> new ControlPayload(buf.readVarInt(), buf.readByte(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：请回 count 个数据包（服务端 tick 驱动，用来产生持续的上行流）。 */
    public record PollPayload(int sessionId, int count) implements CustomPacketPayload {
        public static final Type<PollPayload> TYPE = new Type<>(id("poll"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PollPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.sessionId);
                    buf.writeVarInt(p.count);
                },
                buf -> new PollPayload(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 数据包：真正的负载。 */
    public record DataPayload(int sessionId, int seq, byte[] data) implements CustomPacketPayload {
        public static final Type<DataPayload> TYPE = new Type<>(id("data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DataPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.sessionId);
                    buf.writeVarInt(p.seq);
                    buf.writeByteArray(p.data);
                },
                buf -> new DataPayload(buf.readVarInt(), buf.readVarInt(), buf.readByteArray(NetBench.MAX_PAYLOAD_BYTES)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 统计/心跳。
     * PING/PONG: a=nonce, b=nanoTime（服务端发起，客户端原样回，服务端算 RTT）
     * CLIENT_REPORT: a=收到的包数, b=收到的字节数（客户端在测试结束时回报，用于 S2C 交叉校验）
     */
    public record StatPayload(int sessionId, byte kind, long a, long b, long c) implements CustomPacketPayload {
        public static final Type<StatPayload> TYPE = new Type<>(id("stat"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.sessionId);
                    buf.writeByte(p.kind);
                    buf.writeLong(p.a);
                    buf.writeLong(p.b);
                    buf.writeLong(p.c);
                },
                buf -> new StatPayload(buf.readVarInt(), buf.readByte(), buf.readLong(), buf.readLong(), buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
