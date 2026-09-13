package cn.apisium.nekomaid.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sniffs the first bytes of an inbound connection to find out whether the peer speaks TLS,
 * plain HTTP or the Minecraft protocol, so that a single public port can serve both the
 * Minecraft server and the NekoMaid web panel.
 *
 * <p>Rules, in order:</p>
 * <ol>
 *   <li>{@code 0x16 0x03} — a TLS handshake record (ClientHello: content type 0x16, version
 *       major 0x03) → {@link Protocol#TLS}. The version byte is part of the test because a
 *       Minecraft handshake of exactly 22 bytes also begins with 0x16 (its VarInt length) —
 *       which is what any 15-character host name, such as {@code ddns.capple.top}, produces.</li>
 *   <li>Second byte {@code 0x00} — a Minecraft handshake packet is a VarInt length followed by
 *       packet id {@code 0x00}. No HTTP method name can produce that byte sequence (its first
 *       two bytes would have to be an ASCII letter followed by NUL), so this decides modern and
 *       legacy pings immediately → {@link Protocol#MINECRAFT}.</li>
 *   <li>A full HTTP request-line prefix ({@code "GET "}, {@code "POST "}, ...) → {@link Protocol#HTTP}.
 *       A method name may straddle TCP segments, so a partial match waits for more bytes.</li>
 *   <li>Anything else → {@link Protocol#MINECRAFT}: unknown traffic must never be swallowed by
 *       the web side.</li>
 * </ol>
 *
 * <p>Subclasses are expected to install the handlers of the detected protocol and then remove
 * this handler; {@link ByteToMessageDecoder} replays the bytes it buffered into the new
 * pipeline, so the first packet is not lost.</p>
 */
public abstract class ProtocolDetector extends ByteToMessageDecoder {
    /** Every HTTP method token we accept; the space is included where the token is unambiguous. */
    private static final String[] HTTP_PREFIXES = {
            "GET ", "POST ", "PUT ", "HEAD ", "OPTIONS ", "DELETE ", "CONNECT ", "PATCH ", "TRACE ", "PRI "
    };
    /** How long a peer may take to reveal its protocol before the connection is dropped. */
    private static final long DECISION_TIMEOUT_SECONDS = 15;

    public enum Protocol { TLS, HTTP, MINECRAFT }

    private boolean decided;

    /** Called exactly once, on the event loop, with the protocol the peer turned out to speak. */
    protected abstract void onProtocol(ChannelHandlerContext ctx, Protocol protocol);

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // A peer that connects but never sends enough bytes to identify itself must not be able to
        // hold the connection (and its buffer) forever.
        ctx.executor().schedule(() -> {
            if (!decided && ctx.channel().isActive()) ctx.close();
        }, DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (decided) return;
        int readable = in.readableBytes();
        if (readable == 0) return;
        int index = in.readerIndex();

        byte first = in.getByte(index);
        // A TLS record starts with content type 0x16 (handshake) followed by major version 0x03.
        // The second byte is part of the test on purpose: a Minecraft handshake begins with its
        // VarInt length, and a 22-byte handshake encodes that length as 0x16 — which is exactly
        // what a 15-character host name such as "ddns.capple.top" produces. Testing only the
        // first byte would drop those players.
        if (first == (byte) 0x16) {
            if (readable < 2) return; // wait for the version byte
            if (in.getByte(index + 1) == (byte) 0x03) {
                decide(ctx, Protocol.TLS);
                return;
            }
            // Not TLS after all — that 0x16 is a Minecraft packet length. Fall through.
        }
        // A Minecraft handshake is a VarInt length followed by packet id 0x00. No HTTP method
        // name can produce that second byte (it would need an ASCII letter then NUL), so this
        // decides modern and legacy pings immediately.
        if (readable >= 2 && in.getByte(index + 1) == 0x00) {
            decide(ctx, Protocol.MINECRAFT);
            return;
        }
        boolean partialMatch = false;
        for (String prefix : HTTP_PREFIXES) {
            int n = Math.min(readable, prefix.length());
            boolean equal = true;
            for (int i = 0; i < n; i++) {
                if (in.getByte(index + i) != (byte) prefix.charAt(i)) {
                    equal = false;
                    break;
                }
            }
            if (!equal) continue;
            if (n == prefix.length()) {
                decide(ctx, Protocol.HTTP);
                return;
            }
            partialMatch = true;
        }
        if (partialMatch) return; // the method name continues in the next TCP segment
        decide(ctx, Protocol.MINECRAFT);
    }

    private void decide(ChannelHandlerContext ctx, Protocol protocol) {
        decided = true;
        onProtocol(ctx, protocol);
    }
}
