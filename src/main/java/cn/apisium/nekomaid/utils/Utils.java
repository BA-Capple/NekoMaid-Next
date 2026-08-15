package cn.apisium.nekomaid.utils;

import cn.apisium.nekomaid.NekoMaid;
import com.alibaba.fastjson2.JSON;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.util.StacktraceDeobfuscator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.event.server.TabCompleteEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
public final class Utils {
    private static boolean hasAsyncTabComplete, canDeobfuscate;
    private static final String JSON_OBJECT = "\ud83c\udf7a";
    public static final boolean IS_PAPER;
    public static final Method classLoaderGetName;

    static {
        boolean isPaper = false;
        try {
            Class.forName("com.destroystokyo.paper.event.server.AsyncTabCompleteEvent");
            hasAsyncTabComplete = true;
            isPaper = true;
        } catch (Throwable ignored) { }
        IS_PAPER = isPaper;
        try {
            Class.forName("io.papermc.paper.util.StacktraceDeobfuscator");
            canDeobfuscate = true;
        } catch (Throwable ignored) { }
        Method classLoaderGetName1 = null;
        try {
            // noinspection JavaReflectionMemberAccess
            classLoaderGetName1 = ClassLoader.class.getMethod("getName");
        } catch (Throwable ignored) { }
        classLoaderGetName = classLoaderGetName1;
    }

    public static boolean hasNBTAPI() { return Bukkit.getPluginManager().getPlugin("NBTAPI") != null; }

    public static double getTPS() {
        try {
            return Bukkit.getTPS()[0];
        } catch (Throwable ignored) { }
        return -1;
    }

    public static double getMSPT() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable ignored) { }
        return -1;
    }

    public static long getPlayerLastPlayTime(@NotNull OfflinePlayer p) {
        return p.getLastLogin();
    }

    @Nullable
    public static List<String> complete(final @NotNull Object[] args) {
        String buffer = (String) args[0];
        try {
            if (hasAsyncTabComplete) {
                AsyncTabCompleteEvent event = new AsyncTabCompleteEvent(Bukkit.getConsoleSender(),
                        Collections.emptyList(), buffer, true, null);
                event.callEvent();
                List<String> completions = event.isCancelled() ? new ArrayList<>() : event.getCompletions();
                if (event.isCancelled() || event.isHandled()) {
                    if (!event.isCancelled() && (TabCompleteEvent.getHandlerList().getRegisteredListeners()).length > 0) {
                        final ArrayList<String> finalCompletions = new ArrayList<>(completions);
                        List<String> legacyCompletions = sync(() -> {
                            TabCompleteEvent syncEvent = new TabCompleteEvent(Bukkit.getConsoleSender(), buffer, finalCompletions);
                            return syncEvent.callEvent() ? syncEvent.getCompletions() : ImmutableList.of();
                        });
                        completions.removeIf(it -> !legacyCompletions.contains(it));
                        loop: for (String completion : legacyCompletions) {
                            for (String it : completions) if (it.equals(completion)) continue loop;
                            completions.add(completion);
                        }
                    }
                    return completions;
                }
            }
            return sync(() -> {
                List<String> offers = Bukkit.getCommandMap().tabComplete(Bukkit.getConsoleSender(), buffer);
                TabCompleteEvent tabEvent = new TabCompleteEvent(Bukkit.getConsoleSender(), buffer, (offers == null)
                        ? Collections.emptyList() : offers);
                Bukkit.getPluginManager().callEvent(tabEvent);
                return tabEvent.isCancelled() ? Collections.emptyList() : tabEvent.getCompletions();
            });
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean canSerialise(Object object) {
        return object == JSONObject.NULL || object instanceof JSONObject ||
                object instanceof JSONArray || object instanceof Number || object instanceof Boolean ||
                object instanceof byte[];
    }

    public static void serialize(Object[] args) {
        for (int i = 0; i < args.length; i++) args[i] = serialize(args[i]);
    }

    public static Object serialize(Object object) {
        try {
            if (canSerialise(object)) return object;
            if (object == null) return JSONObject.NULL;
            if (object instanceof String) {
                return ((String) object).startsWith(JSON_OBJECT) ? object : "\ud83d\udc2e" + object;
            } else return serializeToString(object);
        } catch (Throwable e) {
            if (NekoMaid.INSTANCE.isDebug()) e.printStackTrace();
            throw e;
        }
    }

    public static String serializeToString(Object object) { return JSON_OBJECT + JSON.toJSONString(object); }

    public static int checkUpdate() {
        try {
            OptionalInt current = ServerBuildInfo.buildInfo().buildNumber();
            if (current.isEmpty()) return -1;
            String version = ServerBuildInfo.buildInfo().minecraftVersionId();
            URL url = new URL("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/latest");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(),
                    StandardCharsets.UTF_8))) {
                JSONObject json = new JSONObject(reader.lines().collect(Collectors.joining()));
                int latest = json.getInt("id");
                return latest - current.getAsInt();
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    public static boolean deletePath(Path p) {
        if (!Files.exists(p)) return true;
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e != null) throw e;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean copyPath(Path src, Path dst) {
        try {
            Path dest0 = dst.resolve(src.getFileName());
            if (Files.exists(dest0)) {
                String[] names = src.getFileName().toString().split("\\.");
                if (names.length == 1) dest0 = dst.resolve(src.getFileName() + ".copy");
                else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < names.length - 1; i++) sb.append(names[i]).append('.');
                    sb.append("copy.").append(names[names.length - 1]);
                    dest0 = dst.resolve(sb.toString());
                }
            }
            if (src.equals(dest0)) return false;
            Path dest = dest0;
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path targetDir = dest.resolve(src.relativize(dir));
                    try {
                        Files.copy(dir, targetDir);
                    } catch (FileAlreadyExistsException e) {
                        if (!Files.isDirectory(targetDir)) throw e;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, dest.resolve(src.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    public static Component getCommandComponent(String cmd) {
        return Component.text(cmd).clickEvent(ClickEvent.suggestCommand(cmd));
    }

    public static <T> T sync(Callable<T> fn) {
        return sync(fn, true);
    }

    public static <T> T sync(Callable<T> fn, boolean willThrow) {
        FutureTask<T> future = new FutureTask<>(fn);
        Bukkit.getScheduler().runTask(NekoMaid.INSTANCE, future);
        try {
            return future.get(5L, TimeUnit.SECONDS);
        } catch (Throwable e) {
            if (NekoMaid.INSTANCE.isDebug()) e.printStackTrace();
            if (willThrow) throw new RuntimeException(e);
            else return null;
        }
    }

    public static StackTraceElement[] deobfuscateStacktrace(StackTraceElement[] traceElements) {
        return canDeobfuscate ? StacktraceDeobfuscator.INSTANCE.deobfuscateStacktrace(traceElements) : traceElements;
    }

    public static Thread getMinecraftServerThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName())) return t;
        }
        return null;
    }

    @SuppressWarnings("ProtectedMemberInFinalClass")
    protected static Timings initTimings() {
        // Timings (co.aikar.timings / CustomTimingsHandler) was deprecated and its internals
        // removed in Paper 26.2; the profiler degrades gracefully with a null instance.
        return null;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static void diagnosticConnections(String url, CommandSender sender) {
        new Thread(() -> {
            URL url1;
            try {
                url1 = new URL(url);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            try {
                if (isLoopbackAddress(InetAddress.getByName(url1.getHost()))) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e[NekoMaid] &7Diagnostic: &fThe current address is a loopback address. Please see more: &7https://github.com/neko-craft/NekoMaid/wiki/loopbackAddress.md"));
                }
            } catch (Throwable ignored) { }
            try (BufferedReader reader = Resources.asCharSource(url1, StandardCharsets.UTF_8).openBufferedStream()) {
                String data = reader.lines().collect(Collectors.joining());
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e[NekoMaid] &7Diagnostic Result: " +
                        (data.contains("upgrades") && data.contains("sid") && data.contains("pingInterval")
                            ? "&aSuccessful connection."
                            : "&cUnable to connect.")));
                NekoMaid.INSTANCE.getLogger().info("Diagnostic: Data:\n" + data);
            } catch (Throwable e) {
                NekoMaid.INSTANCE.getLogger().warning("Diagnostic: Connection failed! Please check whether the browser can access the server through the above address!");
                e.printStackTrace();
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e[NekoMaid] &7Diagnostic: &cConnection failed! Please check whether the browser can access the server through the above address!"));
            }
        }).start();
    }

    private static boolean isLoopbackAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(addr) != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
