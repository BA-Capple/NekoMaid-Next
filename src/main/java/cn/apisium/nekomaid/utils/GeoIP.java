package cn.apisium.nekomaid.utils;

import cn.apisium.nekomaid.NekoMaid;
import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

public final class GeoIP {
    private final static String URL_TEMPLATE =
            "https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-City&license_key=%s&suffix=tar.gz";
    private final static long TIME = 30 * 24 * 60 * 60 * 1000L;
    private final Path databaseFile;
    private final NekoMaid main;
    private String url;
    private DatabaseReader reader;

    public GeoIP(NekoMaid main) {
        this.main = main;
        databaseFile = new File(main.getDataFolder(), "GeoIP.db").toPath();
        if (!main.getConfig().getBoolean("geolite2-eula", false)) return;
        // The MaxMind license key belongs to the server owner; never hardcode it in source
        // (AGENTS.md: any third-party API key must be delivered via server config).
        String licenseKey = main.getConfig().getString("geolite2-license-key", "");
        if (licenseKey == null || licenseKey.isEmpty()) {
            main.getLogger().warning("[NekoMaid] geolite2-eula is enabled but geolite2-license-key is not set;"
                    + " skipping GeoLite2 download.");
            return;
        }
        url = String.format(URL_TEMPLATE, licenseKey);
        main.getServer().getScheduler().runTaskAsynchronously(main, () -> {
            downloadDatabase();
            try {
                reader = new DatabaseReader.Builder(databaseFile.toFile()).withCache(new CHMCache()).build();
                main.GLOBAL_DATA.put("hasGeoIP", true);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    @SuppressWarnings("unused")
    public boolean isAvailable() { return reader != null; }

    @Nullable
    public CityResponse queryCity(InetAddress ip) {
        if (reader == null) throw new IllegalStateException("Database is not available!");
        if (ip == null || ip.getHostAddress().contains("127.0.0.1")) return null;
        try {
            return reader.tryCity(ip).orElse(null);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public void downloadDatabase() {
        try {
            if (Files.exists(databaseFile) &&
                    Files.getLastModifiedTime(databaseFile).toMillis() + TIME >= System.currentTimeMillis()) return;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        main.getLogger().info("Downloading GeoLite2 database...");
        try (
                TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new URL(url).openStream()))
        ) {
            ArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) if (entry.getName().endsWith("/GeoLite2-City.mmdb"))
                try (OutputStream outputStream = Files.newOutputStream(databaseFile)) {
                    IOUtils.copy(tar, outputStream);
                    main.getLogger().info("GeoLite2 database is downloaded successfully!");
                }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
