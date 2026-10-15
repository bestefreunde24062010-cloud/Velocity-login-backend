package com.example.accountdata;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;

public class AccountDataPlugin extends JavaPlugin implements Listener {

    // Gemeinsame SQLite-Datenbank mit dem Velocity-Plugin.
    // Bei mehreren Backend-Servern: MySQL verwenden!
    private static final String DB_URL = "jdbc:sqlite:plugins/userlogin/accounts.db";

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        initDatabase();
        getLogger().info("AccountData-Plugin aktiviert!");
    }

    @Override
    public void onDisable() {
        getLogger().info("AccountData-Plugin deaktiviert.");
    }

    private void initDatabase() {
        // Ordner anlegen, falls nicht vorhanden
        File dbFolder = new File(getDataFolder().getParentFile(), "userlogin");
        if (!dbFolder.exists()) {
            dbFolder.mkdirs();
        }

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS account_data (
                    username TEXT PRIMARY KEY,
                    inventory BLOB,
                    armor BLOB,
                    position_world TEXT,
                    position_x REAL,
                    position_y REAL,
                    position_z REAL,
                    position_yaw REAL,
                    position_pitch REAL,
                    xp INTEGER DEFAULT 0,
                    health REAL DEFAULT 20.0,
                    food INTEGER DEFAULT 20
                )
            """);
        } catch (SQLException e) {
            getLogger().severe("DB-Fehler beim Initialisieren: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Beim Join: Inventar + Position des aktuellen Namens laden
    // (Der Name kommt vom Velocity-Proxy via GameProfile)
    // ═══════════════════════════════════════════════════════════
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        // 1 Sekunde Verzögerung, damit der Spieler vollständig geladen ist
        Bukkit.getScheduler().runTaskLater(this, () -> loadAccount(player, username), 20L);
    }

    private void loadAccount(Player player, String username) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM account_data WHERE username = ?")) {

            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                player.sendMessage("§eNeuer Account §f" + username + "§e – keine Daten zum Laden.");
                return;
            }

            // Inventar
            byte[] invBytes = rs.getBytes("inventory");
            if (invBytes != null) {
                player.getInventory().setContents(deserializeItems(invBytes));
            }

            // Rüstung
            byte[] armorBytes = rs.getBytes("armor");
            if (armorBytes != null) {
                ItemStack[] armor = deserializeItems(armorBytes);
                if (armor.length >= 4) {
                    player.getInventory().setHelmet(armor[0]);
                    player.getInventory().setChestplate(armor[1]);
                    player.getInventory().setLeggings(armor[2]);
                    player.getInventory().setBoots(armor[3]);
                }
            }

            // Position
            String world = rs.getString("position_world");
            if (world != null && Bukkit.getWorld(world) != null) {
                Location loc = new Location(
                    Bukkit.getWorld(world),
                    rs.getDouble("position_x"),
                    rs.getDouble("position_y"),
                    rs.getDouble("position_z"),
                    rs.getFloat("position_yaw"),
                    rs.getFloat("position_pitch")
                );
                player.teleport(loc);
            }

            // XP, Health, Food
            player.setLevel(rs.getInt("xp"));
            double health = rs.getDouble("health");
            player.setHealth(Math.max(0.5, Math.min(health, 20.0)));
            player.setFoodLevel(rs.getInt("food"));

            player.sendMessage("§aAccount-Daten von §e" + username + " §ageladen!");
        } catch (SQLException | IOException | ClassNotFoundException e) {
            getLogger().severe("Ladefehler für " + username + ": " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Beim Quit: Inventar + Position des aktuellen Namens speichern
    // ═══════════════════════════════════════════════════════════
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO account_data
                (username, inventory, armor, position_world, position_x, position_y,
                 position_z, position_yaw, position_pitch, xp, health, food)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             """)) {

            ps.setString(1, username);
            ps.setBytes(2, serializeItems(player.getInventory().getContents()));
            ps.setBytes(3, serializeItems(new ItemStack[]{
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
            }));

            Location loc = player.getLocation();
            ps.setString(4, loc.getWorld().getName());
            ps.setDouble(5, loc.getX());
            ps.setDouble(6, loc.getY());
            ps.setDouble(7, loc.getZ());
            ps.setFloat(8, loc.getYaw());
            ps.setFloat(9, loc.getPitch());
            ps.setInt(10, player.getLevel());
            ps.setDouble(11, player.getHealth());
            ps.setInt(12, player.getFoodLevel());

            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            getLogger().severe("Speicherfehler für " + username + ": " + e.getMessage());
        }
    }

    // ─── Inventar Serialisierung ───
    private byte[] serializeItems(ItemStack[] items) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.length);
            for (ItemStack item : items) boos.writeObject(item);
            return baos.toByteArray();
        }
    }

    private ItemStack[] deserializeItems(byte[] data)
            throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int length = bois.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) bois.readObject();
            }
            return items;
        }
    }
                        }
