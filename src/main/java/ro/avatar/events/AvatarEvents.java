package ro.avatar.events;

import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class AvatarEvents extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final List<String> ELEMENTS = List.of("fire", "water", "earth", "air");
    private static final List<String> KEYS = List.of("comet", "solar", "lunar");

    private String active;
    private BossBar bar;
    private long endsAt, totalMs;
    private BukkitTask tickTask, autoTask;
    private boolean prevStorm, prevThunder;
    private World stormWorld;

    @Override public void onEnable() {
        saveDefaultConfig();
        getCommand("avatarevent").setExecutor(this);
        getCommand("avatarevent").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        setupAuto();
    }

    @Override public void onDisable() { if (active != null) stop(false); }

    private void setupAuto() {
        if (autoTask != null) autoTask.cancel();
        if (!getConfig().getBoolean("auto.enabled")) return;
        long period = Math.max(1, getConfig().getInt("auto.check-minutes", 30)) * 1200L;
        autoTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (active != null) return;
            if (new Random().nextInt(100) >= getConfig().getInt("auto.chance-percent", 15)) return;
            List<String> pool = getConfig().getStringList("auto.pool");
            if (pool.isEmpty()) return;
            start(pool.get(new Random().nextInt(pool.size())), -1);
        }, period, period);
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void start(String key, int minutes) {
        ConfigurationSection s = getConfig().getConfigurationSection("events." + key);
        if (s == null) return;
        if (active != null) stop(false);
        active = key;
        int m = minutes > 0 ? minutes : s.getInt("minutes", 10);
        totalMs = m * 60_000L;
        endsAt = System.currentTimeMillis() + totalMs;

        BarColor bc;
        try { bc = BarColor.valueOf(s.getString("color", "WHITE")); } catch (Exception e) { bc = BarColor.WHITE; }
        bar = Bukkit.createBossBar(color(s.getString("title", key)), bc, BarStyle.SOLID);
        bar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);

        if (s.getBoolean("thunder")) {
            stormWorld = Bukkit.getWorlds().get(0);
            prevStorm = stormWorld.hasStorm();
            prevThunder = stormWorld.isThundering();
            stormWorld.setStorm(true);
            stormWorld.setThundering(true);
        }
        Bukkit.broadcastMessage(color(getConfig().getString("prefix", "") + s.getString("announce", "")));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(color(s.getString("title", key)), "", 10, 60, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.8f);
        }
        for (String c : s.getStringList("start-commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private void stop(boolean announce) {
        String key = active;
        active = null;
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        if (bar != null) { bar.removeAll(); bar = null; }
        if (stormWorld != null) {
            stormWorld.setStorm(prevStorm);
            stormWorld.setThundering(prevThunder);
            stormWorld = null;
        }
        ConfigurationSection s = getConfig().getConfigurationSection("events." + key);
        if (s != null) {
            for (String c : s.getStringList("end-commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        }
        if (announce) Bukkit.broadcastMessage(color(getConfig().getString("prefix", "") + "&7Evenimentul s-a incheiat."));
    }

    private void tick() {
        if (active == null) return;
        long left = endsAt - System.currentTimeMillis();
        if (left <= 0) { stop(true); return; }
        bar.setProgress(Math.max(0.0, Math.min(1.0, left / (double) totalMs)));
        ConfigurationSection s = getConfig().getConfigurationSection("events." + active);
        if (s == null) return;
        Particle particle = null;
        try { particle = Particle.valueOf(s.getString("particle", "")); } catch (Exception ignored) {}
        for (Player p : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(p);
            for (String el : ELEMENTS) {
                if (!p.hasPermission("avatar.empire." + el)) continue;
                for (String e : s.getStringList("effects." + el)) applyEffect(p, e);
            }
            if (particle != null) {
                p.getWorld().spawnParticle(particle, p.getLocation().add(0, 2.2, 0), 8, 0.6, 0.3, 0.6, 0.01);
            }
        }
    }

    private void applyEffect(Player p, String spec) {
        String[] parts = spec.split(":");
        PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(parts[0].toLowerCase()));
        if (type == null) { getLogger().warning("Efect necunoscut: " + spec); return; }
        int amp = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        p.addPotionEffect(new PotionEffect(type, 60, amp, true, false, true));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { if (bar != null) bar.addPlayer(e.getPlayer()); }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) return false;
        switch (a[0].toLowerCase()) {
            case "start" -> {
                if (a.length < 2 || !KEYS.contains(a[1].toLowerCase())) { sender.sendMessage("Foloseste: /avatarevent start <comet|solar|lunar> [minute]"); return true; }
                int m = -1;
                if (a.length > 2) try { m = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { sender.sendMessage("Minutele trebuie sa fie un numar."); return true; }
                start(a[1].toLowerCase(), m);
            }
            case "stop" -> { if (active == null) sender.sendMessage("Niciun eveniment activ."); else stop(true); }
            case "status" -> sender.sendMessage(active == null ? "Niciun eveniment activ."
                    : "Activ: " + active + ", mai sunt " + Math.max(0, (endsAt - System.currentTimeMillis()) / 1000) + "s");
            case "reload" -> { reloadConfig(); setupAuto(); sender.sendMessage("Config reincarcat."); }
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("start", "stop", "status", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("start")) return KEYS;
        return List.of();
    }
  }
