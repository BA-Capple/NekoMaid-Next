package cn.apisium.nekomaid.http;

import cn.apisium.nekomaid.NekoMaid;
import cn.apisium.netty.engineio.EngineIoHandler;
import com.google.common.cache.Cache;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.socket.engineio.server.EngineIoServer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * A standalone Netty HTTP server which serves the NekoMaid web panel, socket.io realtime
 * channel, static resources and file upload/download endpoints. Replaces the retired
 * Uniporter dependency.
 *
 * <p>Two listeners can run at the same time, and each has its own TLS setting because they
 * normally sit in different places:</p>
 * <ul>
 *   <li><b>Primary listener</b> ({@code port} / {@code bind-address} / {@code tls}) — serves the
 *       panel directly. It can terminate TLS itself and falls back to a generated self-signed
 *       certificate, so a fresh install is usable without any preparation. Behind a reverse proxy
 *       TLS is left off and the proxy terminates it instead.</li>
 *   <li><b>Shared listener</b> ({@code port-share}) — a single public port serves both the
 *       Minecraft server and this panel. The first bytes of every connection are sniffed by
 *       {@link ProtocolDetector}: TLS is terminated here, plain HTTP is allowed or refused, and
 *       everything else is relayed unchanged to the real Minecraft listener on another port
 *       ({@link RelayChannelHandler}).</li>
 * </ul>
 */
public final class NekoMaidHttpServer {
    private static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;
    /** Uploads bypass the HTTP aggregator and stream to a temporary file up to this disk bound. */
    public static final long MAX_UPLOAD_LENGTH = 1024L * 1024 * 1024;
    private static final String SOCKET_IO_PATH = "/NekoMaid/";
    private static final String UPLOAD_PATH = "/NekoMaidUpload/";
    private static final String DOWNLOAD_PATH = "/NekoMaidDownload/";
    private static final String DETECTOR_NAME = "protocol-detector";
    private static final String SELF_SIGNED_CERT = "self-signed-cert.pem";
    private static final String SELF_SIGNED_KEY = "self-signed-key.pem";

    private final NekoMaid plugin;
    private final Options options;
    private final EngineIoServer engineIoServer;
    private final Cache<String, Path> uploadMap;
    private final Cache<String, Path> downloadMap;
    private final Path staticRoot;
    private SslContext primarySslContext;
    private SslContext sharedSslContext;
    private boolean primaryUsesTls;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventExecutorGroup uploadExecutorGroup;
    private Channel serverChannel;
    private Channel sharedChannel;

    public NekoMaidHttpServer(NekoMaid plugin, Options options, EngineIoServer engineIoServer,
                              Cache<String, Path> uploadMap, Cache<String, Path> downloadMap) {
        this.plugin = plugin;
        this.options = options;
        this.engineIoServer = engineIoServer;
        this.uploadMap = uploadMap;
        this.downloadMap = downloadMap;
        Path p = Paths.get(options.staticPath());
        this.staticRoot = (p.isAbsolute() ? p : plugin.getDataFolder().toPath().resolve(options.staticPath()))
                .toAbsolutePath().normalize();
        extractBundledPanel();
    }

    /** Prefix under which the web panel is stored inside this jar. */
    private static final String BUNDLED_PANEL_PREFIX = "static/";

    /**
     * Unpacks the web panel that ships inside the jar into the static root, so dropping the jar
     * into {@code plugins/} is all it takes to get a working panel. Extraction is keyed to the jar
     * file itself: upgrading the jar refreshes the panel, while ordinary restarts leave it alone.
     */
    private void extractBundledPanel() {
        try {
            // JavaPlugin#getFile is protected, so locate the jar through the class's own code source.
            File jarFile = new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            String fingerprint = jarFile.length() + ":" + jarFile.lastModified();
            Path marker = staticRoot.resolve(".bundled-panel");
            if (Files.exists(marker) && fingerprint.equals(Files.readString(marker).trim())) return;

            int extracted = 0;
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith(BUNDLED_PANEL_PREFIX)) continue;
                    Path target = staticRoot.resolve(name.substring(BUNDLED_PANEL_PREFIX.length())).normalize();
                    if (!target.startsWith(staticRoot)) continue;
                    Files.createDirectories(target.getParent());
                    try (java.io.InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    extracted++;
                }
            }
            Files.createDirectories(staticRoot);
            Files.writeString(marker, fingerprint);
            if (extracted > 0) {
                plugin.getLogger().info("Unpacked " + extracted + " bundled web panel files into " + staticRoot);
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Could not unpack the bundled web panel into " + staticRoot + ": " + e);
            if (plugin.isDebug()) e.printStackTrace();
        }
    }

    public int getPort() {
        return options.port();
    }

    /** Whether the panel currently answers on the port shared with the Minecraft server. */
    public boolean isSharingPort() {
        return options.share().enabled() && sharedChannel != null;
    }

    /** Whether the primary listener ended up serving HTTPS. */
    public boolean isPrimarySecure() {
        return primaryUsesTls;
    }

    public synchronized void start() {
        if (serverChannel != null || sharedChannel != null) return;
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        uploadExecutorGroup = new DefaultEventExecutorGroup(2);
        try {
            Options.Share share = options.share();
            if (options.tls().enabled()) {
                primarySslContext = buildTls(options.tls(), "the web panel");
                if (primarySslContext == null) throw new IllegalStateException("TLS was requested for the web panel");
                primaryUsesTls = true;
            }
            if (share.enabled() && share.tls().enabled()) {
                sharedSslContext = buildTls(share.tls(), "the shared port");
                if (sharedSslContext == null) throw new IllegalStateException("TLS was requested for the shared port");
            }

            if (options.port() > 0) {
                serverChannel = bind(options.bindAddress(), options.port(), false, options.port());
                plugin.getLogger().info("Web panel listening on " + (primaryUsesTls ? "https" : "http")
                        + "://" + options.bindAddress() + ":" + options.port()
                        + (!primaryUsesTls && !share.enabled()
                        ? " (plain HTTP: keep it bound to loopback and put a reverse proxy in front)" : ""));
            } else {
                plugin.getLogger().info("Primary web listener disabled (port <= 0).");
            }

            if (share.enabled()) {
                try {
                    sharedChannel = bind(share.bindAddress(), share.port(), true, share.port());
                } catch (Throwable e) {
                    plugin.getLogger().warning("Failed to bind the shared port " + share.port() + ": "
                            + e.getMessage() + ". If the Minecraft server still uses this port, move it first"
                            + " (server-port=" + share.minecraftPort() + ") and restart the server.");
                    throw e;
                }
                plugin.getLogger().info("Shared port listening on " + share.bindAddress() + ":" + share.port()
                        + " — HTTPS panel" + (share.allowPlainHttp() ? " and plain HTTP" : "")
                        + ", Minecraft relayed to " + share.minecraftHost() + ":" + share.minecraftPort()
                        + (sharedSslContext != null ? " (built-in TLS)" : ""));
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to start web server on port " + options.port() + ": " + e.getMessage());
            if (plugin.isDebug()) e.printStackTrace();
            stop();
        }
    }

    public synchronized void stop() {
        closeQuietly(serverChannel);
        serverChannel = null;
        closeQuietly(sharedChannel);
        sharedChannel = null;
        shutdownGroups();
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null) channel.close().syncUninterruptibly();
    }

    private void shutdownGroups() {
        // Keep the upload executor alive until every child channel has fired its final pipeline
        // events. Shutting it down alongside the worker group races channelInactive/destroy and
        // produces RejectedExecutionException warnings on an otherwise clean Paper shutdown.
        if (bossGroup != null)
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        if (workerGroup != null)
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        if (uploadExecutorGroup != null)
            uploadExecutorGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        bossGroup = null;
        workerGroup = null;
        uploadExecutorGroup = null;
    }

    private Channel bind(String address, int port, boolean shared, int listenPort) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (shared) {
                            p.addLast(DETECTOR_NAME, newDetector(listenPort));
                            return;
                        }
                        SslContext context = primarySslContext;
                        if (context != null) p.addLast("ssl", context.newHandler(ch.alloc()));
                        addHttpHandlers(p, listenPort, context != null);
                    }
                });
        return bootstrap.bind(new InetSocketAddress(address, port)).sync().channel();
    }

    /**
     * Builds the TLS context for one listener. Configured PEM files win; when they are missing or
     * unreadable a self-signed certificate is generated (once, then reused from disk) so a fresh
     * install still speaks HTTPS. Returns {@code null} when TLS cannot be established; the caller
     * refuses to bind that listener instead of silently falling back to HTTP.
     */
    private SslContext buildTls(Options.Tls tls, String scope) {
        try {
            if (!tls.certificate().isEmpty() && !tls.privateKey().isEmpty()) {
                File certificate = resolveFile(tls.certificate());
                File privateKey = resolveFile(tls.privateKey());
                if (certificate.isFile() && privateKey.isFile()) {
                    SslContext context = TlsMaterials.load(certificate, privateKey);
                    plugin.getLogger().info("TLS enabled for " + scope + " (certificate: " + certificate + ")");
                    return context;
                }
                plugin.getLogger().warning("TLS file not found for " + scope + ": "
                        + (certificate.isFile() ? privateKey : certificate)
                        + " — falling back to a self-signed certificate.");
            }
            SslContext context = selfSignedContext();
            plugin.getLogger().warning("TLS for " + scope + " uses a self-signed certificate, so browsers warn on "
                    + "the first visit. Point 'tls.certificate' and 'tls.private-key' at a real certificate"
                    + " (PEM: PKCS#8, SEC1 or PKCS#1) to remove the warning.");
            return context;
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to set up TLS for " + scope + ": " + e);
            if (plugin.isDebug()) e.printStackTrace();
            return null;
        }
    }

    /**
     * Loads the generated self-signed certificate, creating and storing it on first use so the
     * browser warning does not come back on every restart.
     */
    private SslContext selfSignedContext() throws Exception {
        File certificate = new File(plugin.getDataFolder(), SELF_SIGNED_CERT);
        File privateKey = new File(plugin.getDataFolder(), SELF_SIGNED_KEY);
        if (certificate.isFile() && privateKey.isFile()) {
            try {
                return TlsMaterials.load(certificate, privateKey);
            } catch (Exception e) {
                plugin.getLogger().warning("The stored self-signed certificate is unusable (" + e
                        + "); generating a new one.");
            }
        }
        SelfSignedTls.Material material = SelfSignedTls.generate(
                hostnameOf(plugin.getConfig().getString("hostname", "")));
        writePem(certificate, "CERTIFICATE", material.certificate().getEncoded());
        writePem(privateKey, "PRIVATE KEY", material.key().getEncoded());
        return TlsMaterials.load(certificate, privateKey);
    }

    private static void writePem(File file, String type, byte[] der) throws Exception {
        byte[] body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encode(der);
        byte[] header = ("-----BEGIN " + type + "-----\n").getBytes(StandardCharsets.US_ASCII);
        byte[] footer = ("\n-----END " + type + "-----\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + body.length + footer.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        System.arraycopy(footer, 0, out, header.length + body.length, footer.length);
        Files.write(file.toPath(), out);
    }

    /** Strips a trailing {@code :port} from a configured host name, for the certificate subject. */
    private static String hostnameOf(String hostname) {
        if (hostname == null || hostname.isBlank()) return null;
        int colon = hostname.lastIndexOf(':');
        if (colon > 0 && hostname.indexOf(':') == colon) return hostname.substring(0, colon);
        return hostname;
    }

    /** Relative paths are resolved against the plugin data folder, absolute ones are used as-is. */
    private File resolveFile(String configured) {
        File file = new File(configured);
        return file.isAbsolute() ? file : new File(plugin.getDataFolder(), configured);
    }

    /**
     * Installs the plain-HTTP handler stack. When {@code tlsTerminated} is true a {@code SslHandler}
     * is part of the same pipeline, so the WebSocket handshaker advertises {@code wss://} and the
     * browser accepts the upgrade.
     */
    private void addHttpHandlers(ChannelPipeline p, int listenPort, boolean tlsTerminated) {
        p.addLast(new HttpServerCodec());
        // Header normalisation and the upload stream run before aggregation. Large PUT bodies are
        // consumed a chunk at a time on a dedicated executor; socket.io and ordinary requests keep
        // the bounded in-memory aggregator they already relied on.
        p.addLast(new RemoteAddressHandler(options.trustProxyHeaders(), null));
        p.addLast(uploadExecutorGroup, "streaming-upload", new StreamingUploadHandler());
        p.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
        p.addLast(new ChunkedWriteHandler());
        if (options.gzip()) p.addLast(new HttpContentCompressor());
        p.addLast(new WebSocketServerCompressionHandler());
        p.addLast(new EngineIoHandler(engineIoServer, SOCKET_IO_PATH,
                (tlsTerminated ? "wss://" : "ws://") + "localhost:" + listenPort, MAX_CONTENT_LENGTH) {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                if (plugin.isDebug()) cause.printStackTrace();
                else if (ctx.channel().isActive()) ctx.close();
            }
        });
        p.addLast(new MainHttpHandler());
    }

    /** Streams authenticated upload capabilities directly to disk instead of aggregating them. */
    private final class StreamingUploadHandler extends ChannelInboundHandlerAdapter {
        private boolean uploading;
        private Path target;
        private Path temporary;
        private OutputStream output;
        private long written;
        private String origin;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (!uploading && msg instanceof HttpRequest) {
                    HttpRequest request = (HttpRequest) msg;
                    String path = new QueryStringDecoder(request.uri()).path();
                    if (request.method() == HttpMethod.PUT && path.startsWith(UPLOAD_PATH)) {
                        uploading = true;
                        origin = request.headers().get(HttpHeaderNames.ORIGIN);
                        long declared = HttpUtil.getContentLength(request, -1);
                        if (declared > MAX_UPLOAD_LENGTH) {
                            ReferenceCountUtil.release(msg);
                            fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Upload exceeds 1 GiB");
                            return;
                        }
                        String id = path.substring(UPLOAD_PATH.length());
                        target = uploadMap.asMap().remove(id);
                        if (target == null) {
                            ReferenceCountUtil.release(msg);
                            fail(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
                            return;
                        }
                        Path root = Paths.get(".").toRealPath();
                        Path absolute = target.toAbsolutePath().normalize();
                        Path parent = target.getParent();
                        if (!absolute.startsWith(root) || parent == null || !parent.toRealPath().startsWith(root)
                                || Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            ReferenceCountUtil.release(msg);
                            fail(ctx, HttpResponseStatus.FORBIDDEN, "Forbidden");
                            return;
                        }
                        temporary = Files.createTempFile(parent, ".nekomaid-upload-", ".tmp");
                        output = Files.newOutputStream(temporary);
                        // A FullHttpRequest can occur in embedded tests; consume its body here.
                        if (msg instanceof HttpContent) consume(ctx, (HttpContent) msg);
                        ReferenceCountUtil.release(msg);
                        return;
                    }
                }
                if (uploading && msg instanceof HttpContent) {
                    consume(ctx, (HttpContent) msg);
                    ReferenceCountUtil.release(msg);
                    return;
                }
                ctx.fireChannelRead(msg);
            } catch (Throwable e) {
                ReferenceCountUtil.release(msg);
                if (plugin.isDebug()) e.printStackTrace();
                fail(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            }
        }

        private void consume(ChannelHandlerContext ctx, HttpContent content) throws Exception {
            int length = content.content().readableBytes();
            if (written + length > MAX_UPLOAD_LENGTH) {
                fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Upload exceeds 1 GiB");
                return;
            }
            if (length > 0) {
                byte[] bytes = new byte[length];
                content.content().readBytes(bytes);
                output.write(bytes);
                written += length;
            }
            if (content instanceof LastHttpContent) complete(ctx);
        }

        private void complete(ChannelHandlerContext ctx) throws Exception {
            closeOutput();
            Path root = Paths.get(".").toRealPath();
            Path parent = target.getParent();
            if (parent == null || !target.toAbsolutePath().normalize().startsWith(root)
                    || !parent.toRealPath().startsWith(root)
                    || Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                fail(ctx, HttpResponseStatus.FORBIDDEN, "Forbidden");
                return;
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
            temporary = null;
            HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            addCorsHeaders(origin, response);
            HttpUtil.setContentLength(response, 0);
            reset();
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private void fail(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
            cleanup();
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            addCorsHeaders(origin, response);
            HttpUtil.setContentLength(response, response.content().readableBytes());
            reset();
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private void closeOutput() {
            if (output != null) try { output.close(); } catch (Throwable ignored) { }
            output = null;
        }

        private void cleanup() {
            closeOutput();
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Throwable ignored) { }
            temporary = null;
        }

        private void reset() {
            uploading = false;
            target = null;
            written = 0;
            origin = null;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cleanup();
            reset();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (plugin.isDebug()) cause.printStackTrace();
            cleanup();
            reset();
            ctx.close();
        }
    }

    /** Builds the sniffer used on the shared port; it installs the detected protocol's handlers. */
    private ProtocolDetector newDetector(int listenPort) {
        return new ProtocolDetector() {
            @Override
            protected void onProtocol(ChannelHandlerContext ctx, Protocol protocol) {
                ChannelPipeline p = ctx.pipeline();
                switch (protocol) {
                    case TLS -> {
                        if (sharedSslContext == null) {
                            ctx.close();
                            return;
                        }
                        // The SslHandler goes *after* this detector, so removing the detector
                        // replays the buffered ClientHello straight into the TLS engine.
                        p.addAfter(ctx.name(), "ssl", sharedSslContext.newHandler(ctx.alloc()));
                        addHttpHandlers(p, listenPort, true);
                        p.remove(this);
                    }
                    case HTTP -> {
                        if (!options.share().allowPlainHttp()) {
                            sendRawAndClose(ctx, HttpResponseStatus.FORBIDDEN,
                                    "This port only accepts TLS requests; use https:// instead.\n");
                            return;
                        }
                        addHttpHandlers(p, listenPort, false);
                        p.remove(this);
                    }
                    case MINECRAFT -> installRelay(ctx, listenPort);
                }
            }
        };
    }

    /**
     * Relays a Minecraft connection to the real listener. Reads stay paused until the backend is
     * connected, so the handshake bytes buffered by the detector are not lost — removing the
     * detector replays them into the relay.
     */
    private void installRelay(ChannelHandlerContext ctx, int listenPort) {
        Options.Share share = options.share();
        Channel front = ctx.channel();
        front.config().setAutoRead(false);
        Bootstrap bootstrap = new Bootstrap()
                .group(front.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (share.proxyProtocol()) ch.pipeline().addLast(HAProxyMessageEncoder.INSTANCE);
                    }
                });
        bootstrap.connect(share.minecraftHost(), share.minecraftPort()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                plugin.getLogger().warning("Failed to relay a Minecraft connection to " + share.minecraftHost()
                        + ":" + share.minecraftPort() + " (" + future.cause() + ")");
                front.close();
                return;
            }
            Channel back = future.channel();
            if (share.proxyProtocol()) sendProxyHeader(back, front.remoteAddress());
            back.pipeline().addLast(new RelayChannelHandler(front));
            front.pipeline().addLast(new RelayChannelHandler(back));
            front.pipeline().remove(DETECTOR_NAME);
            front.config().setAutoRead(true);
        });
    }

    /**
     * Sends the HAProxy PROXY header so that the Minecraft server (with {@code proxies.proxy-protocol}
     * enabled) keeps seeing the player's real address instead of the relay's loopback address.
     */
    private void sendProxyHeader(Channel back, SocketAddress remote) {
        try {
            if (!(remote instanceof InetSocketAddress)) return;
            InetSocketAddress inet = (InetSocketAddress) remote;
            InetAddress address = inet.getAddress();
            if (address == null) return;
            InetSocketAddress destination = (InetSocketAddress) back.remoteAddress();
            InetAddress destinationAddress = destination.getAddress();
            if (destinationAddress == null) throw new IllegalStateException("Unresolved Minecraft relay target");
            boolean ipv6 = address instanceof Inet6Address || destinationAddress instanceof Inet6Address;
            String sourceIp = address.getHostAddress();
            String destinationIp = destinationAddress.getHostAddress();
            if (ipv6 && !(address instanceof Inet6Address)) sourceIp = "::ffff:" + sourceIp;
            if (ipv6 && !(destinationAddress instanceof Inet6Address)) destinationIp = "::ffff:" + destinationIp;
            HAProxyMessage message = new HAProxyMessage(HAProxyProtocolVersion.V1, HAProxyCommand.PROXY,
                    ipv6 ? HAProxyProxiedProtocol.TCP6 : HAProxyProxiedProtocol.TCP4,
                    sourceIp, destinationIp,
                    inet.getPort(), destination.getPort());
            back.writeAndFlush(message);
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to send the PROXY protocol header: " + e);
        }
    }

    /** Writes a hand-built response on a connection where no HTTP codec is installed yet. */
    private static void sendRawAndClose(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + status.code() + " " + status.reasonPhrase() + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n" + body;
        ctx.writeAndFlush(Unpooled.copiedBuffer(response, StandardCharsets.UTF_8))
                .addListener(ChannelFutureListener.CLOSE);
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
                if (uri.startsWith(UPLOAD_PATH) && request.method() == HttpMethod.OPTIONS) {
                    HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
                    addCorsHeaders(request, response);
                    HttpUtil.setContentLength(response, 0);
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else if (uri.startsWith(DOWNLOAD_PATH)
                        && (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD)) {
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

        private void handleDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
            String id = request.uri().substring(DOWNLOAD_PATH.length());
            Path file = downloadMap.getIfPresent(id);
            if (file == null || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                sendSimple(ctx, HttpResponseStatus.FORBIDDEN, "text/plain; charset=UTF-8", "Forbidden");
                return;
            }
            try {
                Path root = Paths.get(".").toRealPath();
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(root)) {
                    sendSimple(ctx, HttpResponseStatus.FORBIDDEN, "text/plain; charset=UTF-8", "Forbidden");
                    return;
                }
                RandomAccessFile raf = new RandomAccessFile(realFile.toFile(), "r");
                long length = raf.length();
                ByteRange range;
                try {
                    range = parseRange(request.headers().get(HttpHeaderNames.RANGE), length);
                } catch (IllegalArgumentException ignored) {
                    raf.close();
                    FullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                    response.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */" + length)
                            .set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES);
                    addCorsHeaders(request, response);
                    HttpUtil.setContentLength(response, 0);
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                long offset = range == null ? 0 : range.start();
                long responseLength = range == null ? length : range.length();
                HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                        range == null ? HttpResponseStatus.OK : HttpResponseStatus.PARTIAL_CONTENT);
                HttpUtil.setContentLength(response, responseLength);
                response.headers().set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES);
                if (range != null) response.headers().set(HttpHeaderNames.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + range.end() + "/" + length);
                // ChunkedFile is a file region, not an HTTP content chunk. Do not advertise gzip.
                response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "identity");
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM)
                        .set(HttpHeaderNames.CONTENT_DISPOSITION,
                                "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(
                                        realFile.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20"));
                addCorsHeaders(request, response);
                ctx.write(response);
                if (request.method() == HttpMethod.HEAD || responseLength == 0) raf.close();
                else ctx.write(new ChunkedFile(raf, offset, responseLength, 8192), ctx.newProgressivePromise());
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
            // Opening the bare panel address (no connection parameter) would land on the front-end's
            // "enter a server address" screen. Send the browser to the same address carrying this
            // panel's own endpoint, taken from the Host it was requested with — so any IP, domain or
            // port works without configuring anything.
            if (rawPath.equals("/") && !request.uri().contains("?")) {
                String host = request.headers().get(HttpHeaderNames.HOST);
                if (host != null && !host.isEmpty()) {
                    FullHttpResponse redirect = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
                    redirect.headers().set(HttpHeaderNames.LOCATION, "/?"
                            + java.net.URLEncoder.encode(host + "/NekoMaid", StandardCharsets.UTF_8));
                    HttpUtil.setContentLength(redirect, 0);
                    ctx.writeAndFlush(redirect);
                    return;
                }
            }
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

    /** Parses a single RFC 7233 bytes range. Multiple ranges intentionally return 416. */
    private static ByteRange parseRange(String header, long length) {
        if (header == null || header.isBlank()) return null;
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || length <= 0)
            throw new IllegalArgumentException("Unsupported range");
        String value = header.substring(6).trim();
        int dash = value.indexOf('-');
        if (dash < 0) throw new IllegalArgumentException("Malformed range");
        try {
            if (dash == 0) {
                long suffix = Long.parseLong(value.substring(1));
                if (suffix <= 0) throw new IllegalArgumentException("Empty suffix");
                long start = Math.max(0, length - suffix);
                return new ByteRange(start, length - 1);
            }
            long start = Long.parseLong(value.substring(0, dash));
            long end = dash == value.length() - 1 ? length - 1 : Long.parseLong(value.substring(dash + 1));
            if (start < 0 || start >= length || end < start) throw new IllegalArgumentException("Invalid range");
            return new ByteRange(start, Math.min(end, length - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed range", e);
        }
    }

    private record ByteRange(long start, long end) {
        long length() { return end - start + 1; }
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
        addCorsHeaders(origin, response);
    }

    private static void addCorsHeaders(String origin, HttpResponse response) {
        if (origin != null) {
            response.headers()
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,HEAD,POST,PUT,OPTIONS")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "origin, content-type, accept, range")
                    .set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS,
                            "accept-ranges, content-range, content-length, content-disposition");
        }
    }

    /** Immutable view of the settings this server needs, read from {@code config.yml}. */
    public record Options(int port, String bindAddress, String staticPath, boolean gzip,
                          boolean trustProxyHeaders, Tls tls, Share share) {

        public static Options fromConfig(NekoMaid plugin) {
            FileConfiguration config = plugin.getConfig();
            return new Options(
                    config.getInt("port", 12334),
                    config.getString("bind-address", "127.0.0.1"),
                    config.getString("static-path", "static"),
                    config.getBoolean("gzip", true),
                    config.getBoolean("trust-proxy-headers", false),
                    Tls.fromConfig(config.getConfigurationSection("tls")),
                    Share.fromConfig(config.getConfigurationSection("port-share")));
        }

        /** Settings for the port shared with the Minecraft server. */
        public record Share(boolean enabled, String bindAddress, int port, String minecraftHost,
                            int minecraftPort, boolean proxyProtocol, boolean allowPlainHttp, Tls tls) {

            public static Share fromConfig(ConfigurationSection section) {
                if (section == null || !section.getBoolean("enabled", false)) {
                    return new Share(false, "0.0.0.0", 25565, "127.0.0.1", 25566, false, false,
                            new Tls(false, "", ""));
                }
                return new Share(true,
                        section.getString("bind-address", "0.0.0.0"),
                        section.getInt("port", 25565),
                        section.getString("minecraft-host", "127.0.0.1"),
                        section.getInt("minecraft-port", 25566),
                        section.getBoolean("proxy-protocol", false),
                        section.getBoolean("allow-plain-http", false),
                        Tls.fromConfig(section.getConfigurationSection("tls")));
            }
        }

        /** Built-in TLS settings for one listener. */
        public record Tls(boolean enabled, String certificate, String privateKey) {

            public static Tls fromConfig(ConfigurationSection section) {
                if (section == null) return new Tls(false, "", "");
                return new Tls(section.getBoolean("enabled", false),
                        section.getString("certificate", ""),
                        section.getString("private-key", ""));
            }
        }
    }
}
