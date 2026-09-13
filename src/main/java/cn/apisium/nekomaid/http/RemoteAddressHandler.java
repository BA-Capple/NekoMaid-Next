package cn.apisium.nekomaid.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Establishes which source address the panel is allowed to trust.
 *
 * <p>When NekoMaid listens on a directly reachable port (the shared-port mode) any client can
 * send {@code X-Real-IP} / {@code X-Forwarded-For} itself, so those headers are stripped and the
 * address the TCP connection actually came from is written in their place. Behind a reverse proxy
 * the operator sets {@code trust-proxy-headers: true}, and the proxy is responsible for
 * overwriting the headers (nginx's {@code proxy_set_header X-Real-IP $remote_addr} does this).</p>
 *
 * <p>Downstream code reads {@code X-Real-IP} only, so both modes look identical to it.</p>
 */
public final class RemoteAddressHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    /** Header the panel's authentication code reads the source address from. */
    public static final String REAL_IP_HEADER = "X-Real-IP";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final boolean trustProxyHeaders;

    public RemoteAddressHandler(boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
        if (!trustProxyHeaders) {
            msg.headers().remove(REAL_IP_HEADER);
            msg.headers().remove(FORWARDED_FOR_HEADER);
            String address = socketAddressOf(ctx.channel().remoteAddress());
            if (address != null) msg.headers().set(REAL_IP_HEADER, address);
        }
        // SimpleChannelInboundHandler releases the message, so retain it for the next handler.
        ctx.fireChannelRead(msg.retain());
    }

    private static String socketAddressOf(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) return null;
        InetSocketAddress inet = (InetSocketAddress) address;
        return inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
    }
}
