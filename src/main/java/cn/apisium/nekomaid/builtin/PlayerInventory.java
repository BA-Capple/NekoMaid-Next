package cn.apisium.nekomaid.builtin;

import cn.apisium.nekomaid.utils.ItemData;
import cn.apisium.nekomaid.utils.Utils;
import cn.apisium.nekomaid.NekoMaid;
import com.janboerman.invsee.spigot.InvseePlusPlus;
import com.janboerman.invsee.spigot.api.EnderSpectatorInventory;
import com.janboerman.invsee.spigot.api.InvseeAPI;
import com.janboerman.invsee.spigot.api.MainSpectatorInventory;
import com.janboerman.invsee.spigot.api.response.SpectateResponse;
import com.lishid.openinv.IOpenInv;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class PlayerInventory {
    private boolean hasOpenInv, hasInvSee;
    public PlayerInventory(NekoMaid main) {
        // AGENTS.md §4: probe the API classes before any cast. Major plugin versions change
        // package names (e.g. Multiverse 4.x -> 5.x); a failed probe disables the integration
        // instead of crashing the Netty thread.
        boolean hasOpenInvApi = false, hasInvSeeApi = false;
        try {
            Class.forName("com.lishid.openinv.IOpenInv");
            hasOpenInvApi = true;
        } catch (Throwable ignored) { }
        try {
            Class.forName("com.janboerman.invsee.spigot.InvseePlusPlus");
            hasInvSeeApi = true;
        } catch (Throwable ignored) { }
        if (hasOpenInvApi && main.getServer().getPluginManager().getPlugin("OpenInv") != null) {
            hasOpenInv = true;
            main.GLOBAL_DATA.put("hasOfflineInventorySupport", true);
        } else if (hasInvSeeApi) {
            Plugin plugin = main.getServer().getPluginManager().getPlugin("InvSeePlusPlus");
            try {
                if (plugin != null && ((InvseePlusPlus) plugin).offlinePlayerSupport()) {
                    hasInvSee = true;
                    main.GLOBAL_DATA.put("hasOfflineInventorySupport", true);
                }
            } catch (Throwable e) {
                if (main.isDebug()) e.printStackTrace();
            }
        }
        main.onConnected(main, client -> {
            if (!client.hasPermission("inventory")) return; // secondary tokens: no inventory editing
            // OpenInv and Bukkit inventories must be loaded, read, mutated and saved on
            // the server thread. In particular, OpenInv 5.x schedules offline-player
            // loading back to that thread and Paper rejects asynchronous saveData().
            client.onWithAck("inventory:fetchInv",
                            (Function<Object[], Object>) args -> Utils.sync(() -> fetch(args, false)))
                    .onWithAck("inventory:fetchEnderChest",
                            (Function<Object[], Object>) args -> Utils.sync(() -> fetch(args, true)))
                    .onWithAck("inventory:set",
                            (Function<Object[], Boolean>) args -> Utils.sync(() -> set(args)));
        });
    }

    private Object fetch(Object[] args, boolean enderChest) {
        try (FakePlayer player = getFakePlayer((String) args[0])) {
            if (player != null) return getInventoryItems(enderChest ? player.getEnderChest() : player.getInventory());
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    private boolean set(Object[] args) {
        try (FakePlayer player = getFakePlayer((String) args[1])) {
            if (player == null) return false;
            Inventory inv = "PLAYER".equals(args[0]) ? player.getInventory() : player.getEnderChest();
            try {
                int to = (int) args[2], from = (int) args[4];
                String data = (String) args[3];
                return ItemData.setInventoryItem(inv, to, data, from);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    @Nullable
    private Object getInventoryItems(Inventory inv) {
        return inv == null ? null : ItemData.fromInventory(inv);
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private FakePlayer getFakePlayer(String name) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(name);
        if (player.isOnline()) return new OnlinePlayer(player.getPlayer());
        if (!player.hasPlayedBefore()) return null;
        if (hasOpenInv) return new OpenInvPlayer(player);
        if (hasInvSee) return new InvSeePlayer(name);
        return null;
    }

    private interface FakePlayer extends AutoCloseable {
        Inventory getInventory() throws Throwable;
        Inventory getEnderChest() throws Throwable;
    }

    private static class OnlinePlayer implements FakePlayer {
        private final Player player;
        OnlinePlayer(Player player) { this.player = player; }
        @Override
        public Inventory getInventory() { return player.getInventory(); }
        @Override
        public Inventory getEnderChest() { return player.getEnderChest(); }
        @Override
        public void close() { NekoMaid.INSTANCE.getServer().getScheduler().runTask(NekoMaid.INSTANCE, player::updateInventory); }
    }

    private static class OpenInvPlayer implements FakePlayer {
        private final Player player;
        @SuppressWarnings("ConstantConditions")
        OpenInvPlayer(OfflinePlayer offlinePlayer) {
            player = ((IOpenInv) Bukkit.getPluginManager().getPlugin("OpenInv")).loadPlayer(offlinePlayer);
        }
        @Override
        public Inventory getInventory() { return player.getInventory(); }
        @Override
        public Inventory getEnderChest() { return player.getEnderChest(); }
        @Override
        public void close() { player.saveData(); }
    }

    private static class InvSeePlayer implements FakePlayer {
        private final InvseeAPI api;
        private final String name;
        private MainSpectatorInventory mainInv;
        private EnderSpectatorInventory enderInv;
        @SuppressWarnings("ConstantConditions")
        InvSeePlayer(String name) {
            this.name = name;
            api = ((InvseePlusPlus) Bukkit.getPluginManager().getPlugin("InvSeePlusPlus")).getApi();
        }
        @Override
        public Inventory getInventory() throws Throwable {
            SpectateResponse<MainSpectatorInventory> resp = api
                    .mainSpectatorInventory(name, "").get(30, TimeUnit.SECONDS);
            if (!resp.isSuccess()) return null;
            return mainInv = resp.getInventory();
        }
        @Override
        public Inventory getEnderChest() throws Throwable {
            SpectateResponse<EnderSpectatorInventory> resp = api
                    .enderSpectatorInventory(name, "").get(30, TimeUnit.SECONDS);
            if (!resp.isSuccess()) return null;
            return enderInv = resp.getInventory();
        }
        @Override
        public void close() {
            if (mainInv != null) api.saveInventory(mainInv);
            if (enderInv != null) api.saveEnderChest(enderInv);
        }
    }
}
