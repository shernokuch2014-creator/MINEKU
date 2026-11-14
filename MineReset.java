package uz.minereset;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class MineReset extends JavaPlugin implements Listener {

    static class MineHolder implements InventoryHolder {
        public Inventory getInventory() { return null; }
    }

    private Economy eco;
    private final Map<UUID, Location[]> sel = new HashMap<>();
    private String activeTier = null;
    private long activeEnd = 0;
    private boolean resetting = false;
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupEconomy();
        if (eco == null) {
            getLogger().severe("Vault iqtisodiyoti topilmadi!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) eco = rsp.getProvider();
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    // ---------- Buyruqlar ----------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (!p.hasPermission("minereset.admin")) { p.sendMessage(c("&cRuxsat yo'q!")); return true; }
        if (a.length == 0) { p.sendMessage(c("&e/mine wand | set | reload")); return true; }
        switch (a[0].toLowerCase()) {
            case "wand" -> {
                ItemStack w = new ItemStack(Material.GOLDEN_AXE);
                ItemMeta m = w.getItemMeta();
                m.setDisplayName(c("&6Mine Wand"));
                w.setItemMeta(m);
                p.getInventory().addItem(w);
                p.sendMessage(c("&aChap tugma = 1-nuqta, o'ng tugma = 2-nuqta."));
            }
            case "set" -> {
                Location[] l = sel.get(p.getUniqueId());
                if (l == null || l[0] == null || l[1] == null) {
                    p.sendMessage(c("&cAvval wand bilan ikkala nuqtani belgilang!"));
                    return true;
                }
                ConfigurationSection r = getConfig().createSection("region");
                r.set("world", l[0].getWorld().getName());
                r.set("x1", Math.min(l[0].getBlockX(), l[1].getBlockX()));
                r.set("y1", Math.min(l[0].getBlockY(), l[1].getBlockY()));
                r.set("z1", Math.min(l[0].getBlockZ(), l[1].getBlockZ()));
                r.set("x2", Math.max(l[0].getBlockX(), l[1].getBlockX()));
                r.set("y2", Math.max(l[0].getBlockY(), l[1].getBlockY()));
                r.set("z2", Math.max(l[0].getBlockZ(), l[1].getBlockZ()));
                saveConfig();
                p.sendMessage(c("&aShaxta hududi saqlandi!"));
            }
            case "reload" -> { reloadConfig(); p.sendMessage(c("&aQayta yuklandi.")); }
        }
        return true;
    }

    // ---------- Wand ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.GOLDEN_AXE || !it.hasItemMeta()
                || !it.getItemMeta().getDisplayName().equals(c("&6Mine Wand"))) return;
        if (e.getClickedBlock() == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Location[] l = sel.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            l[0] = e.getClickedBlock().getLocation();
            p.sendMessage(c("&e1-nuqta belgilandi."));
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            l[1] = e.getClickedBlock().getLocation();
            p.sendMessage(c("&e2-nuqta belgilandi."));
        }
    }

    // ---------- Chat: "mineimator" ----------
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (!e.getMessage().trim().equalsIgnoreCase("mineimator")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> openGui(p));
    }

    // ---------- GUI ----------
    private void openGui(Player p) {
        Inventory inv = Bukkit.createInventory(new MineHolder(), 27, c("&c&lSHAXTA YANGILAGICHI"));
        ConfigurationSection tiers = getConfig().getConfigurationSection("tiers");
        for (String key : tiers.getKeys(false)) {
            ConfigurationSection t = tiers.getConfigurationSection(key);
            Material icon = Material.matchMaterial(t.getString("icon", "STONE"));
            ItemStack item = new ItemStack(icon == null ? Material.STONE : icon);
            ItemMeta m = item.getItemMeta();
            m.setDisplayName(c(t.getString("name")));
            boolean active = key.equals(activeTier) && System.currentTimeMillis() < activeEnd;
            List<String> lore = new ArrayList<>();
            lore.add(c("&7Keraksiz: &f" + t.getInt("junk") + "%"));
            lore.add(c("&7Vaqt: &f" + t.getInt("minutes") + " daqiqa"));
            lore.add(c("&7Narx: &e" + t.getInt("cost")));
            lore.add(active ? c("&a✔ FAOL (" + left() + ")") : c("&c✘ Faol emas"));
            m.setLore(lore);
            item.setItemMeta(m);
            inv.setItem(t.getInt("slot"), item);
        }
        p.openInventory(inv);
    }

    private String left() {
        long s = Math.max(0, (activeEnd - System.currentTimeMillis()) / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MineHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ConfigurationSection tiers = getConfig().getConfigurationSection("tiers");
        for (String key : tiers.getKeys(false)) {
            ConfigurationSection t = tiers.getConfigurationSection(key);
            if (t.getInt("slot") != e.getRawSlot()) continue;
            buy(p, key, t);
            p.closeInventory();
        }
    }

    private void buy(Player p, String key, ConfigurationSection t) {
        if (resetting) { p.sendMessage(c("&cShaxta hozir yangilanmoqda, kuting.")); return; }
        if (key.equals(activeTier) && System.currentTimeMillis() < activeEnd) {
            p.sendMessage(c("&cBu rejim allaqachon faol!")); return;
        }
        if (!getConfig().isConfigurationSection("region") || getConfig().getString("region.world") == null) {
            p.sendMessage(c("&cShaxta hududi belgilanmagan! Admin /mine set qilsin.")); return;
        }
        double cost = t.getDouble("cost");
        if (!eco.has(p, cost)) { p.sendMessage(c("&cPulingiz yetmaydi! Kerak: &e" + (int) cost)); return; }
        eco.withdrawPlayer(p, cost);
        activeTier = key;
        activeEnd = System.currentTimeMillis() + t.getInt("minutes") * 60_000L;
        Bukkit.broadcastMessage(c("&6" + p.getName() + " &7shaxtani " + t.getString("name") + " &7rejimga yangiladi!"));
        resetMine(t);
    }

    // ---------- Shaxtani yangilash (qatlam-qatlam, lag bo'lmasligi uchun) ----------
    private void resetMine(ConfigurationSection t) {
        World w = Bukkit.getWorld(getConfig().getString("region.world"));
        if (w == null) return;
        int x1 = getConfig().getInt("region.x1"), y1 = getConfig().getInt("region.y1"), z1 = getConfig().getInt("region.z1");
        int x2 = getConfig().getInt("region.x2"), y2 = getConfig().getInt("region.y2"), z2 = getConfig().getInt("region.z2");

        Material junk = Material.matchMaterial(getConfig().getString("junk-block", "STONE"));
        if (junk == null) junk = Material.STONE;
        int junkPct = t.getInt("junk");

        List<Material> mats = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (String s : t.getStringList("ores")) {
            String[] sp = s.split(":");
            Material m = Material.matchMaterial(sp[0]);
            if (m == null) continue;
            int wt = sp.length > 1 ? Integer.parseInt(sp[1]) : 1;
            mats.add(m); weights.add(wt); total += wt;
        }
        final int totalW = total;
        final Material junkF = junk;
        resetting = true;

        new BukkitRunnable() {
            int y = y1;
            public void run() {
                if (y > y2) { resetting = false; cancel(); return; }
                for (int x = x1; x <= x2; x++)
                    for (int z = z1; z <= z2; z++) {
                        Material m = junkF;
                        if (rnd.nextInt(100) >= junkPct && totalW > 0) {
                            int r = rnd.nextInt(totalW), acc = 0;
                            for (int i = 0; i < mats.size(); i++) {
                                acc += weights.get(i);
                                if (r < acc) { m = mats.get(i); break; }
                            }
                        }
                        w.getBlockAt(x, y, z).setType(m, false);
                    }
                y++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }
}
