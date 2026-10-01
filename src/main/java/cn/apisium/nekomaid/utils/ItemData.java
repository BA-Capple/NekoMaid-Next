package cn.apisium.nekomaid.utils;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import de.tr7zw.nbtapi.NBTItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal", "unused"})
public class ItemData {
    public String type, name, icon;
    public int amount;
    public boolean hasEnchants;
    public String nbt;
    /**
     * Structured fields are applied through Bukkit's data-component aware ItemMeta API. The raw
     * NBT remains the base, so components which this editor does not understand survive a visual
     * edit instead of being reconstructed or discarded by the browser.
     */
    public boolean structured;
    public String nameComponent;
    public String[] loreComponents;
    public Map<String, Integer> enchantments;
    public Integer damage;
    public Boolean unbreakable;
    @JSONField(serialize=false)
    private ItemStack itemStack;
    private static Constructor<?> nbtContainer;
    private static final GsonComponentSerializer COMPONENTS = GsonComponentSerializer.gson();

    @SuppressWarnings("deprecation")
    public ItemData(ItemStack is) {
        Objects.requireNonNull(is);
        itemStack = is;
        type = is.getType().name();
        icon = getIcon(is.getType());
        ItemMeta im = is.getItemMeta();
        if (im.hasDisplayName()) name = im.getDisplayName();
        structured = true;
        Component displayName = im.displayName();
        if (displayName != null) nameComponent = COMPONENTS.serialize(displayName);
        List<Component> lore = im.lore();
        if (lore != null) loreComponents = lore.stream().map(COMPONENTS::serialize).toArray(String[]::new);
        enchantments = new LinkedHashMap<>();
        im.getEnchants().forEach((enchantment, level) -> enchantments.put(enchantment.getKey().toString(), level));
        if (im instanceof Damageable) damage = ((Damageable) im).getDamage();
        unbreakable = im.isUnbreakable();
        amount = is.getAmount();
        hasEnchants = hasEnhance(is);
        if (Utils.hasNBTAPI()) nbt = ((Object) NBTItem.convertItemtoNBT(is)).toString();
    }

    private ItemData() { }

    public static ItemData fromString(String str) {
        return JSONObject.parseObject(str, ItemData.class);
    }

    public int getAmount() { return amount; }

    public ItemStack getItemStack() {
        if (itemStack == null) {
            Material t = Material.getMaterial(type);
            Objects.requireNonNull(t);
            if (Utils.hasNBTAPI() && nbt != null) itemStack = NBTAPIWrapper.convertNBTtoItem(nbt);
            else itemStack = new ItemStack(t, amount);
            if (itemStack.getAmount() != amount) itemStack.setAmount(amount);
            if (structured) applyStructuredMeta(itemStack);
        }
        return itemStack;
    }

    @SuppressWarnings("deprecation")
    private void applyStructuredMeta(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(nameComponent == null || nameComponent.isEmpty()
                ? null : COMPONENTS.deserialize(nameComponent));
        if (loreComponents == null || loreComponents.length == 0) meta.lore(null);
        else meta.lore(java.util.Arrays.stream(loreComponents).map(COMPONENTS::deserialize).toList());
        meta.removeEnchantments();
        if (enchantments != null) enchantments.forEach((key, level) -> {
            NamespacedKey namespacedKey = NamespacedKey.fromString(key);
            Enchantment enchantment = namespacedKey == null ? null : Enchantment.getByKey(namespacedKey);
            if (enchantment != null && level != null) meta.addEnchant(enchantment, level, true);
        });
        if (meta instanceof Damageable && damage != null) ((Damageable) meta).setDamage(Math.max(0, damage));
        if (unbreakable != null) meta.setUnbreakable(unbreakable);
        stack.setItemMeta(meta);
    }

    /**
     * Atomically edits or moves one slot. A move uses the server-side source item and swaps the
     * displaced target back into the source slot, so cancelled browser drags never delete items
     * and a forged client payload cannot duplicate them.
     */
    public static boolean setInventoryItem(Inventory inventory, int to, String data, int from) {
        if (to < 0 || to >= inventory.getSize()) return false;
        if (from >= 0) {
            if (from >= inventory.getSize()) return false;
            if (from == to) return true;
            ItemStack source = inventory.getItem(from);
            if (source == null || source.getType() == Material.AIR) return false;
            ItemStack displaced = inventory.getItem(to);
            inventory.setItem(to, source);
            inventory.setItem(from, displaced);
            return true;
        }
        inventory.setItem(to, data == null ? null : fromString(data).getItemStack());
        return true;
    }

    private static String getIcon(Material type) {
        switch (type) {
            case DEBUG_STICK: return "stick";
            case ENCHANTED_GOLDEN_APPLE: return "golden_apple";
            case TIPPED_ARROW: return "arrow";
            default: return null;
        }
    }

    private static boolean hasEnhance(ItemStack is) {
        if (is.getItemMeta().hasEnchants()) return true;
        switch (is.getType()) {
            case ENCHANTED_BOOK:
            case ENCHANTED_GOLDEN_APPLE:
            case DEBUG_STICK: return true;
            case SPLASH_POTION:
            case LINGERING_POTION:
            case POTION: return ((PotionMeta) is.getItemMeta()).hasColor();
            default: return false;
        }
    }

    public static ItemData[] fromInventory(Inventory inv) {
        ItemStack[] contents = inv.getContents();
        ItemData[] arr = new ItemData[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            arr[i] = it == null || it.getType() == Material.AIR ? null : new ItemData(it);
        }
        return arr;
    }
}
