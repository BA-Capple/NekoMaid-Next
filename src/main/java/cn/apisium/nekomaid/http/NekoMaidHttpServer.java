package cn.apisium.nekomaid.http;

import cn.apisium.nekomaid.NekoMaid;
import cn.apisium.netty.engineio.EngineIoHandler;
import com.google.common.cache.Cache;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.socket.engineio.server.EngineIoServer;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * A standalone Netty HTTP server which serves the NekoMaid web panel, socket.io
 * realtime channel, static resources and file upload/download endpoints.
 * Replaces the retired Uniporter dependency.
 */
public final class NekoMaidHttpServer {
    private static final int MAX_CONTENT_LENGTH = 5 * 1024 * 1024; // 5MB
    private static final String SOCKET_IO_PATH = "/NekoMaid/";
    private static final String UPLOAD_PATH = "/NekoMaidUpload/";
    private static final String DOWNLOAD_PATH = "/NekoMaidDownload/";

    private final NekoMaid plugin;
    private final int port;
    private final EngineIoServer engineIoServer;
    private final Cache<String, Path> uploadMap;
    private final Cache<String, Path> downloadMap;
    private final Path staticRoot;
    private final boolean gzip;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NekoMaidHttpServer(NekoMaid plugin, int port, EngineIoServer engineIoServer,
                              Cache<String, Path> uploadMap, Cache<String, Path> downloadMap,
                              String staticPath, boolean gzip) {
        this.plugin = plugin;
        this.port = port;
        this.engineIoServer = engineIoServer;
        this.uploadMap = uploadMap;
        this.downloadMap = downloadMap;
        Path p = Paths.get(staticPath);
        this.staticRoot = (p.isAbsolute() ? p : plugin.getDataFolder().toPath().resolve(staticPath))
                .toAbsolutePath().normalize();
        this.gzip = gzip;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() {
        if (serverChannel != null) return;
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                            p.addLast(new ChunkedWriteHandler());
                            if (gzip) p.addLast(new HttpContentCompressor());
                            p.addLast(new WebSocketServerCompressionHandler());
                            p.addLast(new EngineIoHandler(engineIoServer, SOCKET_IO_PATH,
                                    "ws://localhost:" + port, MAX_CONTENT_LENGTH) {
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    if (plugin.isDebug()) cause.printStackTrace();
                                    else if (ctx.channel().isActive()) ctx.close();
                                }
                            });
                            p.addLast(new MainHttpHandler());
                        }
                    });
            // Bind to loopback only: external TLS is terminated by nginx on 12333, which
            // reverse-proxies to this plain-HTTP listener. InetSocketAddress("127.0.0.1", ...)
            // keeps the web panel off the public network.
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", port)).sync().channel();
            plugin.getLogger().info("Web server listening on http://127.0.0.1:" + port + " (TLS terminated by nginx)");
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to start web server on port " + port + ": " + e.getMessage());
            if (plugin.isDebug()) e.printStackTrace();
            shutdownGroups();
        }
    }

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        shutdownGroups();
    }

    private void shutdownGroups() {
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        bossGroup = null;
        workerGroup = null;
    }

    /**
     * Handles static resources and file upload/download endpoints that fall through
     * the socket.io handler.
     */
    private final class MainHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            try {
                String uri = request.uri();
                if (uri.startsWith(UPLOAD_PATH) && request.method() == HttpMethod.PUT) {
                    handleUpload(ctx, request);
                } else if (uri.startsWith(DOWNLOAD_PATH) && request.method() == HttpMethod.GET) {
                    handleDownload(ctx, request);
                } else {
                    handleStatic(ctx, request);
                }
            } catch (Throwable e) {
                if (plugin.isDebug()) e.printStackTrace();
                sendSimple(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                        "Internal Server Error");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (plugin.isDebug()) cause.printStackTrace();
            if (ctx.channel().isActive()) ctx.close();
        }

        private void handleUpload(ChannelHandlerContext ctx, FullHttpRequest request) {
            String id = request.uri().substring(UPLOAD_PATH.length());
            Path target = uploadMap.getIfPresent(id);
            if (target == null) {
                sendSimple(ctx, HttpResponseStatus.NOT_FOUND, "text/plain; charset=UTF-8", "Not Found");
                return;
            }
            try {
                // Defense in depth: the capability was issued against a validated, symlink-free
                // path, but re-check here so a symlink swapped in during the (<=15 min) window
                // cannot redirect the write outside the server root.
                if (Files.isSymbolicLink(target) ||
                        (target.getParent() != null && Files.isSymbolicLink(target.getParent()))) {
                    sendSimple(ctx, HttpResponseStatus.FORBIDDEN, "text/plain; charset=UTF-8", "Forbidden");
                    return;
                }
                byte[] data = new byte[request.content().readableBytes()];
                request.content().readBytes(data);
                Files.write(target, data);
                HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                addCorsHeaders(request, response);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } catch (Throwable e) {
                if (plugin.isDebug()) e.printStackTrace();
                sendSimple(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                        "Internal Server Error");
            }
        }

        private void handleDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
            String id = request.uri().substring(DOWNLOAD_PATH.length());
            Path file = downloadMap.getIfPresent(id);
            if (file == null || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                sendSimple(ctx, HttpResponseStatus.FORBIDDEN, "text/plain; charset=UTF-8", "Forbidden");
                return;
            }
            try {
                RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
                long length = raf.length();
                HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                HttpUtil.setContentLength(response, length);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM)
                        .set(HttpHeaderNames.CONTENT_DISPOSITION,
                                "attachment; filename=" + file.getFileName().toString());
                addCorsHeaders(request, response);
                ctx.write(response);
                ctx.write(new ChunkedFile(raf, 0, length, 8192), ctx.newProgressivePromise());
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
            } catch (Throwable e) {
                if (plugin.isDebug()) e.printStackTrace();
                sendSimple(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                        "Internal Server Error");
            }
        }

        private void handleStatic(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
                sendSimple(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8",
                        "Method Not Allowed");
                return;
            }
            // Resolve the request path safely against the static root (resolved once in constructor).
            String rawPath = new QueryStringDecoder(request.uri()).path();
            if (rawPath.equals("/")) rawPath = "/index.html";
            Path target = staticRoot.resolve(rawPath.startsWith("/") ? rawPath.substring(1) : rawPath)
                    .normalize();
            if (!target.startsWith(staticRoot)) {
                sendSimple(ctx, HttpResponseStatus.FORBIDDEN, "text/plain; charset=UTF-8", "Forbidden");
                return;
            }
            File file = target.toFile();
            if (file.isDirectory()) file = new File(file, "index.html");
            if (!file.exists() || !file.isFile()) {
                sendSimple(ctx, HttpResponseStatus.NOT_FOUND, "text/plain; charset=UTF-8", "Not Found");
                return;
            }
            try {
                // Symlink defense: resolve the real path and re-check it stays inside the static root.
                Path realFile = file.toPath().toRealPath();
                Path realRoot = staticRoot.toRealPath();
                if (!realFile.startsWith(realRoot)) {
                    sendSimple(ctx, HttpResponseStatus.FORBIDDEN, "text/plain; charset=UTF-8", "Forbidden");
                    return;
                }
                byte[] data = Files.readAllBytes(realFile);
                String mime = Files.probeContentType(realFile);
                if (mime == null) mime = HttpHeaderValues.APPLICATION_OCTET_STREAM.toString();
                if (mime.equalsIgnoreCase("text/html")) mime += "; charset=UTF-8";
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, mime);
                HttpUtil.setContentLength(response, data.length);
                addCorsHeaders(request, response);
                boolean keepAlive = HttpUtil.isKeepAlive(request);
                if (!keepAlive) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.writeAndFlush(response);
                if (!keepAlive) ctx.close();
            } catch (Throwable e) {
                if (plugin.isDebug()) e.printStackTrace();
                sendSimple(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                        "Internal Server Error");
            }
        }
    }

    private static void sendSimple(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType,
                                   String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        HttpUtil.setContentLength(response, body.getBytes(StandardCharsets.UTF_8).length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void addCorsHeaders(HttpRequest request, HttpResponse response) {
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            response.headers()
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,OPTIONS")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "origin, content-type, accept");
        }
    }
}
