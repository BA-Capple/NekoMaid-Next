package cn.apisium.nekomaid;

import cn.apisium.nekomaid.builtin.BuiltinPlugins;
import cn.apisium.nekomaid.utils.GeoIP;
import cn.apisium.nekomaid.utils.OshiWrapper;
import cn.apisium.nekomaid.utils.Totp;
import cn.apisium.nekomaid.utils.Utils;
import cn.apisium.nekomaid.http.NekoMaidHttpServer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ArrayListMultimap;
import io.socket.engineio.server.EngineIoServer;
import io.socket.socketio.server.SocketIoAdapter;
import io.socket.socketio.server.SocketIoNamespace;
import io.socket.socketio.server.SocketIoServer;
import io.socket.socketio.server.SocketIoSocket;
import org.bstats.bukkit.Metrics;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.CachedServerIcon;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.stream.Collectors;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public final class NekoMaid extends JavaPlugin implements Listener {
    private final static String URL_MESSAGE = ChatColor.translateAlternateColorCodes('&',
            "&e[NekoMaid] &fOpen this url to manage your server: &7"),
            SUCCESS = ChatColor.translateAlternateColorCodes('&',
                    "&e[NekoMaid] &aSuccess!"),
            VERSION = ChatColor.translateAlternateColorCodes('&',
                    "&e[NekoMaid] &7Version: &a"),
            DIAGNOSTIC = ChatColor.translateAlternateColorCodes('&',
                    "&e[NekoMaid] &7Diagnosing URL: &a");
    public static NekoMaid INSTANCE;
    { INSTANCE = this; }

    private final ArrayListMultimap<org.bukkit.plugin.Plugin, Consumer<Client>> connectListeners = ArrayListMultimap.create();
    private final HashMap<String, Map.Entry<org.bukkit.plugin.Plugin, NekoMaidCommand>> pluginCommands = new HashMap<>();
    private final HashMap<String, HashMap<String, AbstractMap.SimpleEntry<Consumer<Client>,
            Consumer<Client>>>> pluginPages = new HashMap<>();

    private BuiltinPlugins plugins;
    private NekoMaidHttpServer httpServer;
    private EngineIoServer engineIoServer;
    private final ConcurrentHashMap<SocketIoSocket, String[]> pages = new ConcurrentHashMap<>();
    private final HashMap<SocketIoSocket, HashMap<String, Client>> clients = new HashMap<>();
    private final Cache<String, Boolean> tempTokens = CacheBuilder.newBuilder().maximumSize(10)
            .expireAfterWrite(60, TimeUnit.MINUTES).build();
    /** Brute-force protection for the OTP check: failed attempts per token, lock-out after a threshold. */
    private final Cache<String, Integer> otpFailures = CacheBuilder.newBuilder().maximumSize(256)
            .expireAfterWrite(10, TimeUnit.MINUTES).build();
    private final Cache<String, Long> otpLocks = CacheBuilder.newBuilder().maximumSize(256)
            .expireAfterWrite(6, TimeUnit.MINUTES).build();
    /** Pending TOTP secrets for secondary tokens whose first-time 2fa setup is in progress. */
    private final Cache<String, String> pendingSecrets = CacheBuilder.newBuilder().maximumSize(256)
            .expireAfterWrite(10, TimeUnit.MINUTES).build();
    /** Short-lived session credentials (session id -> token + source IP) issued after OTP success. */
    private final Cache<String, SessionInfo> sessions = CacheBuilder.newBuilder().maximumSize(1024)
            .expireAfterWrite(8, TimeUnit.HOURS).build();
    private static final class SessionInfo {
        final String token;
        final String ip;
        SessionInfo(String token, String ip) { this.token = token; this.ip = ip; }
    }
    private static final int MAX_OTP_FAILURES = 5;
    private static final long OTP_LOCK_MILLIS = 5 * 60 * 1000L;
    private final JSONObject pluginScripts = new JSONObject();
    private URLClassLoader loader;
    private GeoIP geoIP;
    private boolean debug;
    public SocketIoNamespace io;
    @SuppressWarnings("ProtectedMemberInFinalClass")
    protected Map<String, Set<SocketIoSocket>> mRoomSockets;

    final public JSONObject GLOBAL_DATA = new JSONObject();

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            // Keep the config (which stores TOTP secrets) readable by its owner only on POSIX hosts.
            java.nio.file.Files.setPosixFilePermissions(getDataFolder().toPath().resolve("config.yml"),
                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (Throwable ignored) { }
        if (Utils.hasNBTAPI()) { GLOBAL_DATA.put("hasNBTAPI", true); }
        GLOBAL_DATA
                .put("plugins", pluginScripts)
                .put("version", getServer().getVersion())
                .put("onlineMode", getServer().getOnlineMode())
                .put("pluginVersion", getDescription().getVersion());
        syncGlobalData();
        if (Utils.IS_PAPER) GLOBAL_DATA.put("isPaper", true);
        try {
            CachedServerIcon icon = getServer().getServerIcon();
            if (icon != null && !icon.isEmpty()) GLOBAL_DATA.put("icon", icon.getData());
        } catch (Throwable ignored) { }
        // Ensure a primary token entry exists. Migrate the legacy single-token config
        // (token + two-factor.secret) into the unified tokens list on first load.
        migrateTokenConfig();
        if (getPrimaryTokenEntry() == null) {
            Map<String, Object> primary = new java.util.LinkedHashMap<>();
            primary.put("name", "primary");
            primary.put("token", UUID.randomUUID().toString());
            primary.put("secret", "");
            primary.put("primary", true);
            List<Map<String, Object>> tokens = getTokenList();
            tokens.add(0, primary);
            getConfig().set("tokens", tokens);
            saveConfig();
        }

        engineIoServer = new EngineIoServer();
        io = new SocketIoServer(engineIoServer).namespace("/");
        try {
            Field field = SocketIoAdapter.class.getDeclaredField("mRoomSockets");
            field.setAccessible(true);
            mRoomSockets = (Map<String, Set<SocketIoSocket>>) field.get(io.getAdapter());
        } catch (Throwable e) {
            e.printStackTrace();
            setEnabled(false);
            return;
        }
        io.on("connection", arr -> {
            SocketIoSocket client = (SocketIoSocket) arr[0];
            Object obj = client.getConnectData();
            if (!(obj instanceof JSONObject)) {
                client.send("!");
                client.disconnect(false);
                return;
            }
            JSONObject connectData = (JSONObject) obj;
            String token = connectData.optString("token", "");
            String sessionToken = connectData.optString("session", "");
            boolean sessionAuth = false;
            if (sessionToken != null && !sessionToken.isEmpty()) {
                SessionInfo si = sessions.getIfPresent(sessionToken);
                if (si != null && si.ip.equals(remoteIpOf(client)) && isTokenStillValid(si.token)) {
                    token = si.token;
                    sessionAuth = true;
                }
            }
            if (token == null || token.isEmpty() || token.length() > 100) {
                client.send("!");
                client.disconnect(false);
                return;
            }
            // Resolve the token to its TOTP secret and role. Primary and secondary tokens
            // MUST pass their own two-factor code to connect (fail-closed); temporary
            // tokens issued via `nm temp` are intentionally single-factor (no TOTP).
            String secret = null;
            boolean primary = false;
            Map<String, Object> tokenEntry = getTokenEntry(token);
            boolean isTemp = tokenEntry == null && tempTokens.getIfPresent(token) != null;
            if (tokenEntry != null) {
                Object s = tokenEntry.get("secret");
                secret = s instanceof String ? (String) s : null;
                primary = Boolean.TRUE.equals(tokenEntry.get("primary"));
            }
            // Brute-force lockout: reject while this token is temporarily locked.
            String lockKey = token + ":" + remoteIpOf(client);
            Long lockedUntil = otpLocks.getIfPresent(lockKey);
            if (lockedUntil != null && lockedUntil > System.currentTimeMillis()) {
                client.send("!");
                client.disconnect(false);
                return;
            }
            boolean hasSecret = secret != null && !secret.isEmpty();
            if (sessionAuth) {
                // Valid session credential (bound to IP): skip the OTP/setup path entirely.
                // no-op — fall through to issue a fresh session below.
            } else if (isTemp) {
                // Temporary tokens (nm temp) are single-factor by design: no TOTP required.
                // no-op — fall through to the normal connection path below.
            } else if (hasSecret) {
                if (!Totp.isValid(secret, connectData.optString("otp", ""))) {
                    Integer prev = otpFailures.getIfPresent(lockKey);
                    int fails = prev == null ? 1 : prev + 1;
                    otpFailures.put(lockKey, fails);
                    if (fails >= MAX_OTP_FAILURES) {
                        otpLocks.put(lockKey, System.currentTimeMillis() + OTP_LOCK_MILLIS);
                        otpFailures.invalidate(lockKey);
                        getLogger().warning("Too many failed two-factor attempts; locked for 5 minutes.");
                    }
                    client.send("twoFactorRequired");
                    client.disconnect(false);
                    return;
                }
                otpFailures.invalidate(lockKey);
            } else if (primary) {
                // Primary token without a secret: refuse (set it up via the console `nm 2fa setup`).
                client.send("!");
                client.disconnect(false);
                return;
            } else {
                // Secondary token without a secret: allow no-2fa if opted in, otherwise force setup.
                if (tokenEntry != null && Boolean.TRUE.equals(tokenEntry.get("allowNo2fa"))) {
                    // no-op: fall through to the normal connection path below (no 2fa).
                } else {
                    beginTwoFactorSetup(client, token);
                    return;
                }
            }
            getClient("NekoMaid", client).primary = primary;
            if (!primary) {
                // Secondary & temporary tokens: read-only + player management by default.
                Set<String> perms = getSecondaryTokenPermissions(token);
                if (perms == null) perms = DEFAULT_SECONDARY_PERMISSIONS;
                getClient("NekoMaid", client).setPermissions(perms);
            }
            // Issue a short-lived session credential so reconnect/reload within 8h skips OTP.
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, new SessionInfo(token, remoteIpOf(client)));
            client.send("session", sessionId);
            if (primary) {
                // Primary token manages secondary tokens from the panel.
                getClient("NekoMaid", client)
                        .onWithAck("token:list", (Function<Object[], Object>) args -> getTokenListForPanel())
                        .onWithAck("token:update", (Function<Object[], Boolean>) args -> updateTokenPermissions(args));
            }
            client.once("disconnect", args -> {
                pages.remove(client);
                clients.remove(client);
            }).on("switchPage", args -> {
                try {
                    String[] oldPageObj = pages.get(client);
                    Client wrappedClient = null;
                    if (oldPageObj != null) {
                        client.leaveRoom(oldPageObj[0] + ":page:" + oldPageObj[1]);
                        HashMap<String, AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>>> oldPages =
                                pluginPages.get(oldPageObj[0]);
                        if (oldPages != null) {
                            AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>> oldPage = oldPages.get(oldPageObj[1]);
                            if (oldPage != null && oldPage.getValue() != null) oldPage.getValue()
                                    .accept(wrappedClient = getClient(oldPageObj[0], client));
                        }
                    }
                    String namespace = (String) args[0], page = (String) args[1];
                    pages.put(client, new String[]{ namespace, page });
                    client.joinRoom(namespace + ":page:" + page);
                    HashMap<String, AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>>> pages = pluginPages.get(namespace);
                    if (pages == null) return;
                    AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>> pageAction = pages.get(page);
                    if (pageAction != null && pageAction.getKey() != null) pageAction.getKey()
                            .accept(wrappedClient == null ? getClient(namespace, client) : wrappedClient);
                } catch (Throwable e) {e.printStackTrace();}
            });
            connectListeners.forEach((k, v) -> v.accept(getClient(k, client)));
            GLOBAL_DATA
                    .put("hasWhitelist", getServer().hasWhitelist())
                    .put("maxPlayers", getServer().getMaxPlayers())
                    .put("spawnRadius", getServer().getSpawnRadius());
            client.send("globalData", GLOBAL_DATA);
        }).on("error", System.out::println);
        geoIP = new GeoIP(this);
        plugins = new BuiltinPlugins(this);
        httpServer = new NekoMaidHttpServer(this, getConfig().getInt("port", 12334), engineIoServer,
                plugins.getFilesManager().getUploadMap(), plugins.getFilesManager().getDownloadMap(),
                getConfig().getString("static-path", "static"), getConfig().getBoolean("gzip", true));
        httpServer.start();

        getServer().getPluginManager().registerEvent(PluginDisableEvent.class, this, EventPriority.NORMAL, (a, e) -> {
            org.bukkit.plugin.Plugin p = ((PluginDisableEvent) e).getPlugin();
            String name = p.getName();
            pluginPages.remove(name);
            clients.forEach((k, v) -> v.remove(name));
            connectListeners.removeAll(p);
            pluginScripts.remove(name);
            pluginCommands.values().removeIf(it -> it.getKey() == p);
        }, this);
        getServer().getPluginManager().registerEvent(org.bukkit.event.player.PlayerCommandPreprocessEvent.class, this,
                EventPriority.MONITOR, (a, e) -> {
                    org.bukkit.event.player.PlayerCommandPreprocessEvent ev =
                            (org.bukkit.event.player.PlayerCommandPreprocessEvent) e;
                    if (ev.isCancelled()) return;
                    String[] parts = ev.getMessage().substring(1).trim().split("\\s+");
                    if (parts.length < 2) return;
                    String cmd = parts[0].toLowerCase(java.util.Locale.ROOT);
                    if (cmd.equals("op") || cmd.equals("minecraft:op")) onOpStateMaybeChanged(parts[1]);
                    else if (cmd.equals("deop") || cmd.equals("minecraft:deop")) onOpStateMaybeChanged(parts[1]);
                }, this);
        getServer().getPluginManager().registerEvent(org.bukkit.event.player.PlayerJoinEvent.class, this,
                EventPriority.MONITOR, (a, e) -> onOpJoinRemind(((org.bukkit.event.player.PlayerJoinEvent) e).getPlayer()), this);
        setupCommands();
        new Metrics(this, 12238);
    }

    /** Called after an /op or /deop command to (re)create or revoke the player's token. */
    public void onOpStateMaybeChanged(@NotNull String playerName) {
        getServer().getScheduler().runTask(this, () -> {
            org.bukkit.OfflinePlayer op = getServer().getOfflinePlayer(playerName);
            if (op.isOp()) {
                // If the player is the primary-token holder, do NOT mint a secondary token or
                // send the (full-access) primary link into chat — they already manage via the primary.
                Map<String, Object> primary = getPrimaryTokenEntry();
                if (primary != null && playerName.equalsIgnoreCase(String.valueOf(primary.get("player")))) {
                    return;
                }
                Map<String, Object> entry = ensureOpToken(playerName);
                org.bukkit.entity.Player online = getServer().getPlayerExact(playerName);
                if (online != null) sendTokenLink(online, (String) entry.get("token"));
            } else {
                revokeOpToken(playerName);
            }
        });
    }

    /** On join, remind op players who still have an unconfigured (no 2fa) token. */
    private void onOpJoinRemind(@NotNull org.bukkit.entity.Player player) {
        if (!player.isOp()) return;
        Map<String, Object> primary = getPrimaryTokenEntry();
        if (primary != null && player.getName().equalsIgnoreCase(String.valueOf(primary.get("player")))) return;
        getServer().getScheduler().runTask(this, () -> {
            Map<String, Object> entry = ensureOpToken(player.getName());
            Object secret = entry.get("secret");
            if (!(secret instanceof String) || ((String) secret).isEmpty()) {
                sendTokenLink(player, (String) entry.get("token"));
            }
        });
    }

    /** Finds or creates the op player's secondary token (secret empty until first setup). */
    private Map<String, Object> ensureOpToken(@NotNull String playerName) {
        for (Map<String, Object> m : getTokenList()) {
            if (playerName.equalsIgnoreCase(String.valueOf(m.get("player")))) return m;
        }
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("name", playerName);
        entry.put("player", playerName);
        entry.put("token", UUID.randomUUID().toString());
        entry.put("secret", "");
        entry.put("permissions", new ArrayList<>(DEFAULT_SECONDARY_PERMISSIONS));
        List<Map<String, Object>> tokens = getTokenList();
        tokens.add(entry);
        getConfig().set("tokens", tokens);
        saveConfig();
        getLogger().info("Created panel token for op player " + playerName);
        return entry;
    }

    /** Revokes the op player's secondary token (called on deop). */
    private void revokeOpToken(@NotNull String playerName) {
        List<Map<String, Object>> tokens = getTokenList();
        boolean removed = tokens.removeIf(m -> !Boolean.TRUE.equals(m.get("primary"))
                && playerName.equalsIgnoreCase(String.valueOf(m.get("player"))));
        if (removed) {
            getConfig().set("tokens", tokens);
            saveConfig();
            getLogger().info("Revoked panel token for deopped player " + playerName);
        }
    }

    /** Sends the management link for a token to an online player. */
    private void sendTokenLink(@NotNull org.bukkit.entity.Player player, @NotNull String token) {
        String url = getConnectUrl(token);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e[NekoMaid] &fManage the server at: &7" + url));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e[NekoMaid] &fThe first visit will ask you to set up two-factor authentication."));
    }

    @SuppressWarnings("deprecation")
    private static void sendUsages(CommandSender sender, String name, String[] arr) {
        String str = ChatColor.GRAY + "/nekomaid " + ChatColor.AQUA + name + " ";
        if (arr == null) sender.sendMessage(Utils.getCommandComponent(str));
        else for (String s : arr) {
            sender.sendMessage(Utils.getCommandComponent(str + Arrays.stream(s.split(" "))
                    .map(it -> (it.startsWith("[") ? ChatColor.GREEN : ChatColor.YELLOW) + it)
                    .collect(Collectors.joining(" "))));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(VERSION + getDescription().getVersion());
        pluginCommands.forEach((k, v) -> sendUsages(sender, k, v.getValue().getUsages()));
    }

    private void syncGlobalData() {
        debug = getConfig().getBoolean("debug", false);
        String bMapKey = getConfig().getString("baidu-map-license-key", "");
        if (bMapKey.isEmpty()) GLOBAL_DATA.remove("bMapKey");
        else GLOBAL_DATA.put("bMapKey", bMapKey);
        String skin = getConfig().getString("skin-url", "");
        if (skin.isEmpty()) GLOBAL_DATA.remove("skinUrl");
        else GLOBAL_DATA.put("skinUrl", skin);
        String head = getConfig().getString("head-url", "");
        if (head.isEmpty()) GLOBAL_DATA.remove("headUrl");
        else GLOBAL_DATA.put("headUrl", head);
    }

    private void setupCommands() {
        registerCommand(this, "reload", (sender, command, label, args) -> {
            if (!sender.hasPermission("neko.maid.admin")) return noPermission(sender);
            reloadConfig();
            syncGlobalData();
            sender.sendMessage(SUCCESS);
            return true;
        });
        registerCommand(this, "help", (sender, command, label, args) -> {
            sendHelp(sender);
            return true;
        });
        registerCommand(this, "temp", (sender, command, label, args) -> {
            String token = UUID.randomUUID().toString();
            tempTokens.put(token, Boolean.TRUE);
            String url = getConnectUrl(token);
            sender.sendMessage(URL_MESSAGE + url);
            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Temporary token valid for 60 minutes, no two-factor required.");
            return true;
        });
        registerCommand(this, "invalidate", (sender, command, label, args) -> {
            if (!sender.hasPermission("neko.maid.admin")) return noPermission(sender);
            tempTokens.cleanUp();
            sender.sendMessage(SUCCESS);
            return true;
        });
        registerCommand(this, "diagnostic", (sender, command, label, args) -> {
            if (!sender.hasPermission("neko.maid.admin")) return noPermission(sender);
            int port = getConnectPort();
            String hostname = "http://" + getConnectHostname(port, "EIO=4&transport=polling");
            sender.sendMessage(DIAGNOSTIC + hostname);
            Utils.diagnosticConnections(hostname, sender);
            return true;
        });
        registerCommand(this, "2fa", new NekoMaidCommand() {
            @Override
            public boolean onCommand(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                                     @NotNull String label, @NotNull String[] args) {
                if (!sender.hasPermission("neko.maid.2fa")) return noPermission(sender);
                String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
                switch (action) {
                    case "status":
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Two-factor authentication is "
                                + (isTwoFactorEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ".");
                        if (getTwoFactorSecret().isEmpty())
                            sender.sendMessage(ChatColor.RED + "[NekoMaid] Note: no TOTP secret is configured. While two-factor"
                                    + " is enforced, the primary token cannot connect to the panel until /" + label
                                    + " 2fa setup is completed.");
                        return true;
                    case "setup": {
                        String secret = Totp.generateSecret();
                        setPrimaryTokenSecret(secret);
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Scan the QR file below, or type this secret into your"
                                + " authenticator app:");
                        sender.sendMessage(ChatColor.AQUA + secret);
                        try {
                            java.nio.file.Path qr = getDataFolder().toPath().resolve("2fa-qr.png");
                            java.nio.file.Files.write(qr, Totp.generateQrPng(getTwoFactorOtpAuthUri(secret), 300));
                            try { java.nio.file.Files.setPosixFilePermissions(qr,
                                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] QR code saved to: " + qr
                                    + " (open via SCP or the Files page)");
                        } catch (Throwable e) {
                            if (isDebug()) e.printStackTrace();
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] otpauth URI: " + getTwoFactorOtpAuthUri(secret));
                        }
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Then run /" + label + " 2fa enable <code>.");
                        return true;
                    }
                    case "enable":
                        if (args.length != 2 || !Totp.isValid(getTwoFactorSecret(), args[1])) {
                            sender.sendMessage(ChatColor.RED + "[NekoMaid] Invalid authenticator code. Run /"
                                    + label + " 2fa setup first if no secret has been configured.");
                            return true;
                        }
                        sender.sendMessage(ChatColor.GREEN + "[NekoMaid] Two-factor authentication enabled.");
                        return true;
                    case "disable":
                        setPrimaryTokenSecret("");
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Two-factor authentication disabled.");
                        sender.sendMessage(ChatColor.RED + "[NekoMaid] Note: two-factor is enforced for every token. With the"
                                + " primary secret cleared, the primary token cannot connect until /" + label
                                + " 2fa setup is run again.");
                        return true;
                    default:
                        return false;
                }
            }

            @Override
            public List<String> onTabComplete(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                                              @NotNull String alias, @NotNull String[] args) {
                if (!sender.hasPermission("neko.maid.2fa")) return Collections.emptyList();
                if (args.length == 1) {
                    List<String> actions = new ArrayList<>(Arrays.asList("status", "setup", "enable", "disable"));
                    actions.removeIf(it -> !it.startsWith(args[0].toLowerCase(Locale.ROOT)));
                    return actions;
                }
                return Collections.emptyList();
            }

            @Override
            public String[] getUsages() {
                return new String[] { "2fa [status|setup|enable <code>|disable]" };
            }
        });
        registerCommand(this, "token", new NekoMaidCommand() {
            @Override
            public boolean onCommand(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                                     @NotNull String label, @NotNull String[] args) {
                if (!sender.hasPermission("neko.maid.admin")) return noPermission(sender);
                String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
                switch (action) {
                    case "create": {
                        if (args.length < 2) {
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Usage: /" + label + " token create <name> [player]");
                            return true;
                        }
                        String name = args[1];
                        if (!name.matches("[\\w-]{1,32}")) {
                            sender.sendMessage(ChatColor.RED + "[NekoMaid] Name must be 1-32 chars of [A-Za-z0-9_-].");
                            return true;
                        }
                        String player = args.length > 2 ? args[2] : null;
                        String t = UUID.randomUUID().toString();
                        String s = Totp.generateSecret();
                        List<Map<String, Object>> tokens = getTokenList();
                        for (Map<String, Object> m : tokens) {
                            if (name.equals(m.get("name"))) {
                                sender.sendMessage(ChatColor.RED + "[NekoMaid] A token named '" + name + "' already exists.");
                                return true;
                            }
                        }
                        Map<String, Object> entry = new java.util.LinkedHashMap<>();
                        entry.put("name", name);
                        if (player != null) entry.put("player", player);
                        entry.put("token", t);
                        entry.put("secret", s);
                        entry.put("permissions", new ArrayList<>(DEFAULT_SECONDARY_PERMISSIONS));
                        tokens.add(entry);
                        getConfig().set("tokens", tokens);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "[NekoMaid] Created token '" + name + "'" +
                                (player == null ? "" : " for " + player) + ".");
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Token: " + t);
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] 2FA secret (enter in authenticator app, or scan):");
                        sender.sendMessage(ChatColor.AQUA + s);
                        try {
                            java.nio.file.Path qr = getDataFolder().toPath().resolve("token-" + name + "-qr.png");
                            java.nio.file.Files.write(qr, Totp.generateQrPng(getTwoFactorOtpAuthUri(s), 300));
                            try { java.nio.file.Files.setPosixFilePermissions(qr,
                                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] QR saved to: " + qr);
                        } catch (Throwable ignored) { }
                        sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Management URL: " + getConnectUrl(t));
                        return true;
                    }
                    case "list": {
                        List<Map<String, Object>> tokens = getTokenList();
                        if (tokens.isEmpty()) {
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] No tokens configured.");
                        } else {
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Tokens:");
                            for (Map<String, Object> m : tokens) {
                                boolean isPrimary = Boolean.TRUE.equals(m.get("primary"));
                                sender.sendMessage(ChatColor.AQUA + "- " + m.get("name")
                                        + (isPrimary ? " [PRIMARY]" : "")
                                        + (m.get("player") == null ? "" : " (" + m.get("player") + ")")
                                        + " token=" + maskToken(String.valueOf(m.get("token"))));
                            }
                        }
                        return true;
                    }
                    case "revoke": {
                        if (args.length < 2) {
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Usage: /" + label + " token revoke <name>");
                            return true;
                        }
                        List<Map<String, Object>> tokens = getTokenList();
                        boolean removed = tokens.removeIf(m -> args[1].equals(m.get("name")));
                        if (!removed) {
                            sender.sendMessage(ChatColor.RED + "[NekoMaid] No token named '" + args[1] + "'.");
                            return true;
                        }
                        getConfig().set("tokens", tokens);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "[NekoMaid] Revoked token '" + args[1] + "'.");
                        return true;
                    }
                    case "bind": {
                        if (args.length < 2) {
                            sender.sendMessage(ChatColor.YELLOW + "[NekoMaid] Usage: /" + label
                                    + " token bind <player> — bind the primary token to your Minecraft name so"
                                    + " /op on yourself reuses it instead of creating a new secondary token.");
                            return true;
                        }
                        Map<String, Object> primary = getPrimaryTokenEntry();
                        if (primary == null) {
                            sender.sendMessage(ChatColor.RED + "[NekoMaid] No primary token found.");
                            return true;
                        }
                        primary.put("player", args[1]);
                        getConfig().set("tokens", getTokenList());
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "[NekoMaid] Primary token bound to player '" + args[1]
                                + "'. /op on that name now reuses the primary token.");
                        return true;
                    }
                    default:
                        return false;
                }
            }

            @Override
            public List<String> onTabComplete(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                                              @NotNull String alias, @NotNull String[] args) {
                if (!sender.hasPermission("neko.maid.admin")) return Collections.emptyList();
                if (args.length == 1) {
                    List<String> actions = new ArrayList<>(Arrays.asList("create", "list", "revoke", "bind"));
                    actions.removeIf(it -> !it.startsWith(args[0].toLowerCase(Locale.ROOT)));
                    return actions;
                }
                if ("revoke".equalsIgnoreCase(args[0]) && args.length == 2) {
                    List<String> names = new ArrayList<>();
                    for (Map<String, Object> m : getTokenList()) names.add(String.valueOf(m.get("name")));
                    names.removeIf(it -> !it.startsWith(args[1].toLowerCase(Locale.ROOT)));
                    return names;
                }
                return Collections.emptyList();
            }

            @Override
            public String[] getUsages() {
                return new String[] { "token [list|create <name> [player]|revoke <name>|bind <player>]" };
            }
        });

        Objects.requireNonNull(getServer().getPluginCommand("nekomaid")).setTabCompleter(this);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTokenList() {
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (Object o : getConfig().getList("tokens", Collections.emptyList())) {
            if (o instanceof Map) tokens.add((Map<String, Object>) o);
        }
        return tokens;
    }

    /** Panel-safe view of secondary tokens (never exposes TOTP secrets, skips the primary). */
    @SuppressWarnings("unchecked")
    private Object getTokenListForPanel() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : getTokenList()) {
            if (Boolean.TRUE.equals(m.get("primary"))) continue; // primary is not panel-managed
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("name", m.get("name"));
            view.put("player", m.get("player"));
            view.put("token", m.get("token"));
            view.put("permissions", m.getOrDefault("permissions", new ArrayList<>(DEFAULT_SECONDARY_PERMISSIONS)));
            view.put("allowNo2fa", Boolean.TRUE.equals(m.get("allowNo2fa")));
            out.add(view);
        }
        return out;
    }

    /** Updates the permission set of a named secondary token. */
    @SuppressWarnings("unchecked")
    private boolean updateTokenPermissions(Object[] args) {
        if (args.length < 2 || !(args[1] instanceof java.util.List)) return false;
        String name = String.valueOf(args[0]);
        List<?> requested = (List<?>) args[1];
        Set<String> perms = new HashSet<>();
        for (Object o : requested) if (o instanceof String) perms.add((String) o);
        perms.removeIf(it -> !KNOWN_PERMISSIONS.contains(it)); // whitelist known features
        boolean allowNo2fa = args.length >= 3 && args[2] instanceof Boolean && (Boolean) args[2];
        List<Map<String, Object>> tokens = getTokenList();
        for (Map<String, Object> m : tokens) {
            if (Boolean.TRUE.equals(m.get("primary"))) continue; // primary permissions are implicit
            if (name.equals(m.get("name"))) {
                m.put("permissions", new ArrayList<>(perms));
                if (allowNo2fa) m.put("allowNo2fa", true);
                else m.remove("allowNo2fa");
                getConfig().set("tokens", tokens);
                saveConfig();
                return true;
            }
        }
        return false;
    }

    private static boolean noPermission(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "[NekoMaid] You do not have permission to use this command.");
        return true;
    }

    /** Masks a token for display in command output: keeps the first 6 and last 4 chars. */
    @NotNull
    private static String maskToken(@NotNull String token) {
        if (token.length() <= 10) return token.replaceAll(".", "*");
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }

    /** Feature permissions granted to secondary/temporary tokens by default. */
    private static final Set<String> DEFAULT_SECONDARY_PERMISSIONS = Set.of(
            "dashboard", "playerList", "players", "worlds", "profiler", "scheduler", "entity", "block");

    /** Every feature permission a secondary token can be granted (whitelist for panel updates). */
    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
            "dashboard", "playerList", "players", "worlds", "profiler", "scheduler", "entity", "block",
            "terminal", "plugins", "files", "config", "editors", "vault", "inventory");

    private boolean isTwoFactorEnabled() {
        Map<String, Object> primary = getPrimaryTokenEntry();
        Object s = primary == null ? null : primary.get("secret");
        return s instanceof String && !((String) s).isEmpty();
    }

    /** Returns the TOTP secret bound to a named secondary token, or null if not found. */
    @Nullable
    private String getSecondaryTokenSecret(@NotNull String token) {
        Map<String, Object> entry = getTokenEntry(token);
        Object secret = entry == null ? null : entry.get("secret");
        return secret instanceof String ? (String) secret : null;
    }

    /** Returns the permissions bound to a named secondary token, or null if the token is not found. */
    @Nullable
    private Set<String> getSecondaryTokenPermissions(@NotNull String token) {
        Map<String, Object> entry = getTokenEntry(token);
        if (entry == null) return null;
        Object perms = entry.get("permissions");
        if (!(perms instanceof List)) return null;
        Set<String> out = new HashSet<>();
        for (Object o : (List<?>) perms) if (o instanceof String) out.add((String) o);
        return out;
    }

    /** Finds a token entry (primary or secondary) by its token value. */
    @SuppressWarnings("unchecked")
    @Nullable
    private Map<String, Object> getTokenEntry(@NotNull String token) {
        for (Map<String, Object> m : getTokenList()) {
            if (token.equals(m.get("token"))) return m;
        }
        return null;
    }

    /** The primary token entry (the one with primary: true). */
    @Nullable
    private Map<String, Object> getPrimaryTokenEntry() {
        for (Map<String, Object> m : getTokenList()) {
            if (Boolean.TRUE.equals(m.get("primary"))) return m;
        }
        return null;
    }

    /** Updates (or clears) the primary token's TOTP secret. */
    private void setPrimaryTokenSecret(@Nullable String secret) {
        Map<String, Object> primary = getPrimaryTokenEntry();
        if (primary == null) return;
        primary.put("secret", secret == null ? "" : secret);
        getConfig().set("tokens", getTokenList());
        saveConfig();
    }

    /** Migrates the legacy `token` + `two-factor.secret` config into the unified tokens list. */
    @SuppressWarnings("unchecked")
    private void migrateTokenConfig() {
        Object legacyToken = getConfig().get("token");
        if (legacyToken == null || !(legacyToken instanceof String) || ((String) legacyToken).isEmpty()) return;
        if (getPrimaryTokenEntry() != null) return; // already migrated
        String secret = getConfig().getString("two-factor.secret", "");
        Map<String, Object> primary = new java.util.LinkedHashMap<>();
        primary.put("name", "primary");
        primary.put("token", legacyToken);
        primary.put("secret", secret == null ? "" : secret);
        primary.put("primary", true);
        List<Map<String, Object>> tokens = getTokenList();
        tokens.add(0, primary);
        getConfig().set("tokens", tokens);
        getConfig().set("token", null);
        getConfig().set("two-factor", null);
        saveConfig();
        getLogger().info("Migrated legacy single-token config into the unified tokens list.");
    }

    @NotNull
    private String getTwoFactorSecret() {
        Map<String, Object> primary = getPrimaryTokenEntry();
        Object s = primary == null ? null : primary.get("secret");
        return s instanceof String ? (String) s : "";
    }

    @NotNull
    private String getTwoFactorOtpAuthUri(@NotNull String secret) {
        String account = getConfig().getString("hostname", "NekoMaid");
        try {
            return "otpauth://totp/" + URLEncoder.encode("NekoMaid:" + account, "UTF-8")
                    + "?secret=" + secret + "&issuer=NekoMaid&algorithm=SHA1&digits=6&period=30";
        } catch (Throwable ignored) {
            return "otpauth://totp/NekoMaid?secret=" + secret + "&issuer=NekoMaid";
        }
    }

    @NotNull
    public GeoIP getGeoIP() { return geoIP; }

    public boolean isDebug() { return debug; }

    public int getClientsCount() { return clients.size(); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.testPermission(sender)) return true;
        if (args.length == 0) sender.sendMessage(URL_MESSAGE + getConnectUrl());
        else {
            Map.Entry<org.bukkit.plugin.Plugin, NekoMaidCommand> it = pluginCommands.get(args[0]);
            if (it == null) sendHelp(sender);
            else if (!it.getValue().onCommand(sender, command, label, Arrays.copyOfRange(args, 1, args.length)))
                sendUsages(sender, args[0], it.getValue().getUsages());
        }
        return true;
    }

    @NotNull
    public String getConnectUrl() { return getConnectUrl(getPrimaryToken()); }

    /** The primary token's secret value (empty if none configured). */
    @NotNull
    private String getPrimaryToken() {
        Map<String, Object> primary = getPrimaryTokenEntry();
        Object t = primary == null ? null : primary.get("token");
        return t instanceof String ? (String) t : "";
    }

    /** Whether a token is still valid (exists in config or as a live temporary token). */
    private boolean isTokenStillValid(@NotNull String token) {
        return getTokenEntry(token) != null || tempTokens.getIfPresent(token) != null;
    }

    /**
     * Best-effort source IP of a socket.io client. Prefers X-Real-IP (set by nginx to
     * $remote_addr, so it overwrites any client-supplied value); falls back to the last
     * X-Forwarded-For hop (nginx appends the real client IP at the end).
     */
    private static String remoteIpOf(SocketIoSocket client) {
        try {
            Map<String, java.util.List<String>> headers = client.getInitialHeaders();
            if (headers != null) {
                java.util.List<String> xr = headers.get("X-Real-IP");
                if (xr != null && !xr.isEmpty()) return xr.get(0).trim();
                java.util.List<String> xff = headers.get("X-Forwarded-For");
                if (xff != null && !xff.isEmpty()) {
                    String[] parts = xff.get(0).split(",");
                    if (parts.length > 0) return parts[parts.length - 1].trim();
                }
            }
        } catch (Throwable ignored) { }
        return "unknown";
    }

    /**
     * First-visit 2fa setup for a secondary token without a secret. Registers only the
     * verification event (no panel features) until the code is confirmed and the secret saved.
     */
    private void beginTwoFactorSetup(SocketIoSocket client, @NotNull String token) {
        String pending = Totp.generateSecret();
        pendingSecrets.put(token, pending);
        client.send("twoFactorSetup", getTwoFactorOtpAuthUri(pending), pending);
        getClient("NekoMaid", client).onWithAck("twoFactor:verify", args -> {
            if (args.length == 0 || !(args[0] instanceof String)) return false;
            String expected = pendingSecrets.getIfPresent(token);
            if (expected == null || !Totp.isValid(expected, (String) args[0])) return false;
            if (setTokenSecret(token, expected)) {
                pendingSecrets.invalidate(token);
                String sid = UUID.randomUUID().toString();
                sessions.put(sid, new SessionInfo(token, remoteIpOf(client)));
                client.send("session", sid);
                return true;
            }
            return false;
        });
    }

    /** Persists a secondary token's TOTP secret once its setup code is verified. */
    private boolean setTokenSecret(@NotNull String token, @NotNull String secret) {
        List<Map<String, Object>> tokens = getTokenList();
        for (Map<String, Object> m : tokens) {
            if (token.equals(m.get("token"))) {
                m.put("secret", secret);
                getConfig().set("tokens", tokens);
                saveConfig();
                getLogger().info("Two-factor secret configured for token " + m.get("name"));
                return true;
            }
        }
        return false;
    }

    public int getConnectPort() {
        return httpServer != null ? httpServer.getPort() : getConfig().getInt("port", 12334);
    }

    @NotNull
    public String getConnectHostname(@NotNull String token) {
        return getConnectHostname(getConnectPort(), token);
    }

    @NotNull
    public String getConnectHostname(int port, @Nullable String token) {
        String url = getConfig().getString("hostname", "");
        return (url.contains(":") ? url : url + ":" + port) + "/NekoMaid" + (token == null ? "" : "?" + token);
    }

    @NotNull
    public String getConnectUrl(@NotNull String token) {
        String custom = getConfig().getString("customAddress", "");
        int port = getConnectPort();
        String url = getConnectHostname(port, token);
        try { url = URLEncoder.encode(url, "UTF-8"); } catch (Throwable ignored) { }
        return custom.isEmpty()
                ? "https://BA-Capple.github.io/NekoMaid-Next/?" + url
                : custom.replace("{token}", token).replace("{hostname}", url);
    }

    @Contract("_, _, _ -> this")
    public NekoMaid registerCommand(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String name, @NotNull NekoMaidCommand cmd) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(name);
        Objects.requireNonNull(cmd);
        pluginCommands.put(name, new AbstractMap.SimpleEntry<>(plugin, cmd));
        return this;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        switch (args.length) {
            case 0: return Collections.emptyList();
            case 1: return new ArrayList<>(pluginCommands.keySet());
            default:
                Map.Entry<org.bukkit.plugin.Plugin, NekoMaidCommand> it = pluginCommands.get(args[0]);
                if (it == null) return Collections.emptyList();
                else return it.getValue().onTabComplete(sender, command, alias, Arrays.copyOfRange(args, 1, args.length));
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop();
        pages.clear();
        connectListeners.clear();
        pluginCommands.clear();
        if (engineIoServer != null) engineIoServer.shutdown();
        if (plugins != null) plugins.disable();
        if (loader != null) try {
            loader.close();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        try {
            OshiWrapper.stop();
        } catch (Throwable ignored) { }
    }

    public int getClientsCountInPage(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String page) {
        if (mRoomSockets == null) return 0;
        Set<SocketIoSocket> set = mRoomSockets.get(plugin.getName() + ":page:" + page);
        return set == null ? 0 : set.size();
    }

    public int getClientsCountInRoom(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String room) {
        if (mRoomSockets == null) return 0;
        Set<SocketIoSocket> set = mRoomSockets.get(plugin.getName() + ":" + room);
        return set == null ? 0 : set.size();
    }

    @Contract("_, _, _ -> this")
    public NekoMaid broadcast(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String name, @NotNull Object... data) {
        if (!clients.isEmpty()) io.broadcast((String) null, plugin.getName() + ":" + name, data);
        return this;
    }

    @Contract("_, _, _, _ -> this")
    public NekoMaid broadcast(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String room,
                              @NotNull String name, @NotNull Object... data) {
        if (getClientsCountInRoom(plugin, room) != 0) {
            Utils.serialize(data);
            String prefix = plugin.getName() + ":";
            io.broadcast(prefix + room, prefix + name, data);
        }
        return this;
    }

    @Contract("_, _, _, _ -> this")
    public NekoMaid broadcastInPage(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String page,
                              @NotNull String name, @NotNull Object... data) {
        return broadcast(plugin, "page:" + page, name, data);
    }

    private Client getClient(org.bukkit.plugin.Plugin plugin, SocketIoSocket client) {
        return getClient(plugin.getName(), client);
    }

    private Client getClient(String plugin, SocketIoSocket client) {
        return clients.computeIfAbsent(client, (a) -> new HashMap<>())
                .computeIfAbsent(plugin, (a) -> new Client(plugin, client));
    }

    @Contract("_, _ -> this")
    public NekoMaid onConnected(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Consumer<Client> fn) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(fn);
        connectListeners.put(plugin, fn);
        return this;
    }

    @Contract("_, _ -> this")
    public NekoMaid on(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Consumer<Client> fn) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(fn);
        connectListeners.put(plugin, fn);
        return this;
    }

    @Contract("_, _, _ -> this")
    @NotNull
    public NekoMaid onSwitchPage(@NotNull org.bukkit.plugin.Plugin plugin,
                             @NotNull String page, @Nullable Consumer<Client> onEnter) {
        return onSwitchPage(plugin, page, onEnter, null);
    }

    @Contract("_, _, _, _ -> this")
    @NotNull
    public NekoMaid onSwitchPage(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String page,
                             @Nullable Consumer<Client> onEnter, @Nullable Consumer<Client> onLeave) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(page);
        if (onEnter != null || onLeave != null)
            pluginPages.computeIfAbsent(plugin.getName(), k -> new HashMap<>())
                    .computeIfAbsent(page, k -> new AbstractMap.SimpleEntry<>(onEnter, onLeave));
        return this;
    }

    @Contract("_, _ -> this")
    @NotNull
    public NekoMaid addScript(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String script) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(script);
        String name = plugin.getName();
        if (!pluginScripts.has(name)) pluginScripts.put(name, new JSONArray());
        pluginScripts.getJSONArray(name).put(script);
        return this;
    }

}
