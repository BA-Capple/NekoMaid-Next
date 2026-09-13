package cn.apisium.nekomaid.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * One direction of a relayed connection: everything read from this channel is forwarded to
 * {@code peer}, and the two sides share their shutdown and back-pressure state.
 *
 * <p>Two instances are installed for a relay — one on each channel, each holding the other as
 * its peer — which keeps the relay symmetric and lets either side close the pair.</p>
 */
public final class RelayChannelHandler extends ChannelInboundHandlerAdapter {
    private final Channel peer;

    public RelayChannelHandler(Channel peer) {
        this.peer = peer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (peer.isActive()) {
            // writeAndFlush takes ownership of the message; no retain needed since we are the
            // only holder.
            peer.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // When the peer's outbound buffer fills up, stop reading from this side so the data stays
        // in the OS/TCP window instead of in memory. Restored as soon as the peer drains.
        peer.config().setAutoRead(ctx.channel().isWritable());
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Let whatever is still buffered reach the other side before tearing it down.
        if (peer.isActive()) {
            peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        } else {
            peer.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
        peer.close();
    }
}
