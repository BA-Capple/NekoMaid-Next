package cn.apisium.nekomaid.builtin;

import cn.apisium.nekomaid.Client;
import cn.apisium.nekomaid.NekoMaid;
import cn.apisium.nekomaid.utils.Utils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.jar.JarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class FilesManager {
    private static final ArchiveStreamFactory archiveFactory = new ArchiveStreamFactory();
    private final static long MAX_SIZE = 4 * 1024 * 1024; // 4MB
    private final NekoMaid main;
    /** The actual server working directory, resolved once so path checks have one stable base. */
    private final Path root;
    /** Directories that secondary (non-primary) tokens may not read or modify. */
    private final List<Path> protectedRoots;
    private final Cache<String, Path> uploadMap = createCache();
    private final Cache<String, Path> downloadMap = createCache();

    public Cache<String, Path> getUploadMap() { return uploadMap; }
    public Cache<String, Path> getDownloadMap() { return downloadMap; }

    public FilesManager(NekoMaid main) {
        this.main = main;
        try {
            root = Paths.get(".").toRealPath();
            protectedRoots = List.of(
                    main.getDataFolder().toPath().toAbsolutePath().normalize(),          // NekoMaid settings
                    root.resolve("plugins").resolve("LuckPerms").toAbsolutePath().normalize() // permission data
            );
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resolve file manager root", e);
        }
        main.onConnected(main, client -> {
            if (!client.hasPermission("files")) return; // secondary tokens: no file management
            client.onWithAck("files:fetch", args -> {
            try {
                Path p = resolveInsideRoot((String) args[0], client);
                if (!Files.isDirectory(p, NOFOLLOW_LINKS)) return null;
                ArrayList<String> dirs = new ArrayList<>(), files = new ArrayList<>();
                try (Stream<Path> stream = Files.list(p)) {
                    stream.forEach(it -> (Files.isDirectory(it) ? dirs : files).add(it.getFileName().toString()));
                }
                return new ArrayList[] { dirs, files };
            } catch (Throwable e) {
                e.printStackTrace();
                return null;
            }
        }).onWithAck("files:content", args -> {
            try {
                Path p = resolveInsideRoot((String) args[0], client);
                if (!Files.exists(p, NOFOLLOW_LINKS)) return 1;
                if (Files.isDirectory(p, NOFOLLOW_LINKS)) return 2;
                if (!Files.isReadable(p) || !Files.isRegularFile(p, NOFOLLOW_LINKS)) return 0;
                if (Files.size(p) > MAX_SIZE) return 3;
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (Throwable e) {
                return null;
            }
        }).onWithAck("files:update", args -> {
            try {
                if (args.length != 2 && args.length != 3) return null;
                Path p = resolveInsideRoot((String) args[0], client);
                if (args.length == 2) return Utils.deletePath(p);
                else if (args[1] != null && !Files.isDirectory(p, NOFOLLOW_LINKS)) {
                    Files.write(p, ((String) args[1]).getBytes(StandardCharsets.UTF_8));
                } else return false;
                return true;
            } catch (Throwable ignored) { return false; }
        }).onWithAck("files:createDirectory", args -> {
            try {
                Path p = resolveInsideRoot((String) args[0], client);
                if (Files.exists(p, NOFOLLOW_LINKS)) return false;
                Files.createDirectory(p);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }).onWithAck("files:rename", args -> {
            if (args.length != 3) return false;
            try {
                Path p0 = resolveInsideRoot((String) args[0], client);
                Path p1 = resolveInsideRoot((String) args[1], client);
                if (!Files.exists(p0, NOFOLLOW_LINKS) || p0.equals(root) || p1.equals(root) || p0.equals(p1)) return false;
                Files.move(p0, p1);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }).onWithAck("files:download", args -> {
            try {
                Path p = resolveInsideRoot((String) args[0], client);
                if (Files.isRegularFile(p, NOFOLLOW_LINKS)) {
                    String id = UUID.randomUUID().toString();
                    downloadMap.put(id, p);
                    return id;
                }
                return false;
            } catch (Throwable ignored) { }
            return false;
        }).onWithAck("files:upload", args -> {
            try {
                Path p = resolveInsideRoot((String) args[0], client);
                if (!Files.exists(p, NOFOLLOW_LINKS)) {
                    String id = UUID.randomUUID().toString();
                    uploadMap.put(id, p);
                    return id;
                }
            } catch (Throwable ignored) { }
            return null;
        }).onWithAck("files:compress", args -> {
            try {
                Path p = resolveInsideRoot((String) args[0], client);
                if (!Files.exists(p, NOFOLLOW_LINKS)) return false;
                if (args.length == 4) {
                    String ext = (String) args[2];
                    String file = args[1] + "." + ext;
                    Path outputName = Paths.get(file);
                    if (outputName.isAbsolute() || outputName.getNameCount() != 1) return false;
                    Path parent = p.equals(root) ? root : p.getParent();
                    Path outFile = validateInsideRoot(parent.resolve(outputName));
                    if (Files.exists(outFile, NOFOLLOW_LINKS)) return false;
                    try (ArchiveOutputStream os = archiveFactory.createArchiveOutputStream((String) args[2],
                            Files.newOutputStream(outFile))) {
                        if (Files.isDirectory(p, NOFOLLOW_LINKS)) {
                            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                                    addEntry(ext, os, parent, f);
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        } else addEntry(ext, os, parent, p);
                        return true;
                    } catch (Throwable e) { e.printStackTrace(); }
                } else if (Files.isRegularFile(p, NOFOLLOW_LINKS)) {
                    try (ArchiveInputStream is = archiveFactory.createArchiveInputStream(
                            new BufferedInputStream(Files.newInputStream(p)))) {
                        Path destDir = p.getParent().toAbsolutePath().normalize();
                        ArchiveEntry archiveEntry;
                        while ((archiveEntry = is.getNextEntry()) != null) {
                            Path outputFile = destDir.resolve(archiveEntry.getName()).normalize();
                            // zip slip 防护：拒绝解压到目标目录之外
                            if (!outputFile.startsWith(destDir)) {
                                main.getLogger().warning("Skipped archive entry with path traversal: "
                                        + archiveEntry.getName());
                                continue;
                            }
                            outputFile = validateInsideRoot(outputFile);
                            if (archiveEntry.isDirectory()) {
                                Files.createDirectories(outputFile);
                                continue;
                            }
                            if (!Files.exists(outputFile.getParent())) Files.createDirectories(outputFile.getParent());
                            try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
                                IOUtils.copy(is, outputStream);
                            }
                        }
                        return true;
                    } catch (Throwable e) { e.printStackTrace(); }
                }
            } catch (Throwable ignored) { }
            return false;
        }).onWithAck("files:copy", (args) -> {
            try {
                Path p1 = resolveInsideRoot((String) args[0], client);
                Path p2 = resolveInsideRoot((String) args[1], client);
                if (Files.exists(p1, NOFOLLOW_LINKS) && Files.isDirectory(p2, NOFOLLOW_LINKS))
                    return Utils.copyPath(p1, p2);
            } catch (Throwable ignored) { }
            return false;
        });
    });
    }

    public void disable() {
        // HTTP endpoints are owned by NekoMaidHttpServer; nothing to unregister here.
    }

    /**
     * Resolves a client-supplied relative path beneath {@link #root}.  Syntactic
     * traversal is rejected after normalization, and every existing component is
     * checked for symbolic links so a link cannot redirect an otherwise valid path.
     */
    private Path resolveInsideRoot(String input) throws IOException {
        Path requested = Paths.get(input);
        if (requested.isAbsolute()) throw new IOException("Absolute paths are not allowed");
        return validateInsideRoot(root.resolve(requested));
    }

    /** Client-aware variant: secondary (non-primary) tokens may not touch protected roots. */
    private Path resolveInsideRoot(String input, Client client) throws IOException {
        Path p = resolveInsideRoot(input);
        if (!client.primary) {
            for (Path r : protectedRoots) {
                if (p.startsWith(r)) {
                    throw new IOException("Secondary tokens cannot access " + r.getFileName());
                }
            }
        }
        return p;
    }

    /** Validates a derived path, including paths created while extracting archives. */
    private Path validateInsideRoot(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("Path escapes file manager root");

        Path current = root;
        for (Path part : root.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in file manager paths");
            }
        }

        Path existing = normalized;
        while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(root)) {
            throw new IOException("Path resolves outside file manager root");
        }
        return normalized;
    }

    private static void addEntry(String ext, ArchiveOutputStream os, Path parent, Path file) throws IOException,
            IllegalArgumentException {
        String path = parent.relativize(file).toString();
        ArchiveEntry archiveEntry;
        switch (ext) {
            case ArchiveStreamFactory.JAR:
                archiveEntry = new JarArchiveEntry(new ZipArchiveEntry(file, path));
                break;
            case ArchiveStreamFactory.ZIP:
                archiveEntry = new ZipArchiveEntry(file, path);
                break;
            case ArchiveStreamFactory.AR:
                archiveEntry = new ArArchiveEntry(file, path);
                break;
            case ArchiveStreamFactory.TAR:
                archiveEntry = new TarArchiveEntry(file, path);
                break;
            case ArchiveStreamFactory.CPIO:
                archiveEntry = new CpioArchiveEntry(file, path);
                break;
            default: throw new IllegalArgumentException("Unsupported archive format: " + ext);
        }

        os.putArchiveEntry(archiveEntry);
        try (InputStream is = Files.newInputStream(file)) { IOUtils.copy(is, os); }
        os.closeArchiveEntry();
    }

    private static Cache<String, Path> createCache() {
        return CacheBuilder.newBuilder().maximumSize(5).expireAfterWrite(15, TimeUnit.MINUTES).build();
    }
}
