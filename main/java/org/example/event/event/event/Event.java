package org.example.event.event.event;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.*;

public final class Event extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    enum EventType { PVP }

    private boolean eventActive = false;
    private EventType eventType = null;
    private String eventName = null;

    private Location eventHall;        // Event-Halle
    private int maxPlayers = 10;       // /event setmax

    // Kit (nur fürs PvP Event)
    private ItemStack[] kitInv = new ItemStack[36];
    private ItemStack[] kitArmor = new ItemStack[4];
    private ItemStack kitOffhand = null;

    // Teilnehmer + Backups
    private final Set<UUID> participants = new HashSet<>();

    // Spieler dürfen pro Event nur 1x joinen
    private final Set<UUID> joinedThisEvent = new HashSet<>();

    private final Map<UUID, ItemStack[]> invBackup = new HashMap<>();
    private final Map<UUID, ItemStack[]> armorBackup = new HashMap<>();
    private final Map<UUID, ItemStack> offhandBackup = new HashMap<>();
    private final Map<UUID, Location> locationBackup = new HashMap<>();

    // Spieler, die nach dem Tod aus dem Event entfernt werden sollen (nach Respawn)
    private final Set<UUID> pendingKickOnRespawn = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadHallFromConfig();
        maxPlayers = getConfig().getInt("maxPlayers", 10);
        loadKitFromConfig();

        PluginCommand cmd = getCommand("event");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().severe("Command 'event' not found in plugin.yml!");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Event Plugin enabled!");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (participants.contains(p.getUniqueId())) restorePlayer(p);
        }
        participants.clear();
        joinedThisEvent.clear();
        pendingKickOnRespawn.clear();
        getLogger().info("Event Plugin disabled!");
    }

    // ---------------- Utilities ----------------

    private void msg(CommandSender s, String m) {
        s.sendMessage(ChatColor.GRAY + "[Event] " + ChatColor.WHITE + m);
    }

    private void broadcast(String m) {
        Bukkit.broadcastMessage(ChatColor.GRAY + "[Event] " + ChatColor.WHITE + m);
    }

    private void loadHallFromConfig() {
        if (!getConfig().contains("hall.world")) return;

        String worldName = getConfig().getString("hall.world", "");
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        double x = getConfig().getDouble("hall.x");
        double y = getConfig().getDouble("hall.y");
        double z = getConfig().getDouble("hall.z");
        float yaw = (float) getConfig().getDouble("hall.yaw");
        float pitch = (float) getConfig().getDouble("hall.pitch");

        eventHall = new Location(w, x, y, z, yaw, pitch);
    }

    private void saveHallToConfig(Location loc) {
        getConfig().set("hall.world", loc.getWorld().getName());
        getConfig().set("hall.x", loc.getX());
        getConfig().set("hall.y", loc.getY());
        getConfig().set("hall.z", loc.getZ());
        getConfig().set("hall.yaw", loc.getYaw());
        getConfig().set("hall.pitch", loc.getPitch());
        saveConfig();
    }

    private String locToString(Location l) {
        return l.getWorld().getName() + " " +
                Math.round(l.getX()) + " " +
                Math.round(l.getY()) + " " +
                Math.round(l.getZ());
    }

    private void backupAndClear(Player p) {
        invBackup.put(p.getUniqueId(), p.getInventory().getContents());
        armorBackup.put(p.getUniqueId(), p.getInventory().getArmorContents());
        offhandBackup.put(p.getUniqueId(), p.getInventory().getItemInOffHand());
        locationBackup.put(p.getUniqueId(), p.getLocation());

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        p.updateInventory();
    }

    private void restorePlayer(Player p) {
        ItemStack[] inv = invBackup.remove(p.getUniqueId());
        ItemStack[] armor = armorBackup.remove(p.getUniqueId());
        ItemStack offhand = offhandBackup.remove(p.getUniqueId());
        Location loc = locationBackup.remove(p.getUniqueId());

        p.getInventory().clear();
        if (inv != null) p.getInventory().setContents(inv);
        if (armor != null) p.getInventory().setArmorContents(armor);
        if (offhand != null) p.getInventory().setItemInOffHand(offhand);
        p.updateInventory();

        if (loc != null) p.teleport(loc);
    }

    private boolean kitIsSet() {
        boolean hasItem = false;

        for (ItemStack it : kitInv) {
            if (it != null && it.getType() != Material.AIR) {
                hasItem = true;
                break;
            }
        }

        boolean hasArmor = false;
        for (ItemStack it : kitArmor) {
            if (it != null && it.getType() != Material.AIR) {
                hasArmor = true;
                break;
            }
        }

        boolean hasOffhand = (kitOffhand != null && kitOffhand.getType() != Material.AIR);

        return hasItem || hasArmor || hasOffhand;
    }

    private void giveKit(Player p) {
        p.getInventory().setContents(kitInv);
        p.getInventory().setArmorContents(kitArmor);
        p.getInventory().setItemInOffHand(kitOffhand);
        p.updateInventory();
    }

    private byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (IOException e) {
            getLogger().warning("Serialize failed: " + e.getMessage());
            return null;
        }
    }

    private Object deserialize(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            getLogger().warning("Deserialize failed: " + e.getMessage());
            return null;
        }
    }

    private void saveKitToConfig() {
        getConfig().set("kit.inv", Base64.getEncoder().encodeToString(Objects.requireNonNullElse(serialize(kitInv), new byte[0])));
        getConfig().set("kit.armor", Base64.getEncoder().encodeToString(Objects.requireNonNullElse(serialize(kitArmor), new byte[0])));
        getConfig().set("kit.offhand", Base64.getEncoder().encodeToString(Objects.requireNonNullElse(serialize(kitOffhand), new byte[0])));
        saveConfig();
    }

    private void loadKitFromConfig() {
        try {
            String invStr = getConfig().getString("kit.inv", "");
            String armorStr = getConfig().getString("kit.armor", "");
            String offhandStr = getConfig().getString("kit.offhand", "");

            if (!invStr.isEmpty()) {
                Object o = deserialize(Base64.getDecoder().decode(invStr));
                if (o instanceof ItemStack[] arr && arr.length == 36) kitInv = arr;
            }
            if (!armorStr.isEmpty()) {
                Object o = deserialize(Base64.getDecoder().decode(armorStr));
                if (o instanceof ItemStack[] arr && arr.length == 4) kitArmor = arr;
            }
            if (!offhandStr.isEmpty()) {
                Object o = deserialize(Base64.getDecoder().decode(offhandStr));
                if (o instanceof ItemStack it) kitOffhand = it;
            }
        } catch (Exception e) {
            getLogger().warning("Kit load failed: " + e.getMessage());
        }
    }

    // ---------------- Commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /event  OR /event join
        if (args.length == 0 || args[0].equalsIgnoreCase("join")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Nur Spieler können joinen.");
                return true;
            }
            if (!eventActive || eventType == null) {
                msg(p, "Aktuell läuft kein Event.");
                return true;
            }
            if (eventHall == null) {
                msg(p, "Event-Halle ist nicht gesetzt. (Admin: /event sethall)");
                return true;
            }
            if (participants.contains(p.getUniqueId())) {
                msg(p, "Du bist schon im Event.");
                return true;
            }
            if (joinedThisEvent.contains(p.getUniqueId())) {
                msg(p, "Du kannst diesem Event nur einmal beitreten.");
                return true;
            }
            if (participants.size() >= maxPlayers) {
                msg(p, "Das Event ist voll! (" + participants.size() + "/" + maxPlayers + ")");
                return true;
            }

            // PVP: Kit muss gesetzt sein (Inventar darf jetzt voll sein!)
            if (eventType == EventType.PVP) {
                if (!kitIsSet()) {
                    msg(p, "PvP-Kit ist noch nicht gesetzt. (Admin: /event setkit)");
                    return true;
                }
            }

            // Backup von allem + clear
            backupAndClear(p);

            participants.add(p.getUniqueId());
            joinedThisEvent.add(p.getUniqueId());

            p.teleport(eventHall);

            if (eventType == EventType.PVP) {
                giveKit(p);
                msg(p, "Du hast das PvP-Kit bekommen. Viel Erfolg!");
            }

            msg(p, "Du bist dem Event \"" + eventName + "\" beigetreten! (" + participants.size() + "/" + maxPlayers + ")");
            return true;
        }

        // /event leave
        if (args[0].equalsIgnoreCase("leave")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Nur Spieler können leaven.");
                return true;
            }
            if (!participants.contains(p.getUniqueId())) {
                msg(p, "Du bist nicht im Event.");
                return true;
            }
            restorePlayer(p);
            participants.remove(p.getUniqueId());
            pendingKickOnRespawn.remove(p.getUniqueId());
            msg(p, "Du hast das Event verlassen.");
            return true;
        }

        // Admin-only: sethall, setmax, setkit, kitclear, start, end
        if (!sender.hasPermission("event.admin")) {
            msg(sender, "Keine Rechte.");
            return true;
        }

        // /event sethall
        if (args[0].equalsIgnoreCase("sethall")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Nur Spieler können die Halle setzen.");
                return true;
            }
            eventHall = p.getLocation();
            saveHallToConfig(eventHall);
            msg(sender, "Event-Halle gesetzt: " + ChatColor.YELLOW + locToString(eventHall));
            return true;
        }

        // /event setmax <zahl>
        if (args[0].equalsIgnoreCase("setmax")) {
            if (args.length < 2) {
                msg(sender, "Nutzung: /event setmax <zahl>");
                return true;
            }
            try {
                int n = Integer.parseInt(args[1]);
                if (n < 1) {
                    msg(sender, "MaxPlayers muss >= 1 sein.");
                    return true;
                }
                maxPlayers = n;
                getConfig().set("maxPlayers", maxPlayers);
                saveConfig();
                msg(sender, "MaxPlayers gesetzt auf: " + ChatColor.YELLOW + maxPlayers);
            } catch (NumberFormatException e) {
                msg(sender, "Bitte eine Zahl angeben.");
            }
            return true;
        }

        // /event setkit
        if (args[0].equalsIgnoreCase("setkit")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Nur Spieler können ein Kit setzen.");
                return true;
            }
            kitInv = p.getInventory().getContents();
            kitArmor = p.getInventory().getArmorContents();
            kitOffhand = p.getInventory().getItemInOffHand();
            saveKitToConfig();
            msg(sender, "PvP-Kit gespeichert (Inventar+Rüstung+Offhand).");
            return true;
        }

        // /event kitclear
        if (args[0].equalsIgnoreCase("kitclear")) {
            kitInv = new ItemStack[36];
            kitArmor = new ItemStack[4];
            kitOffhand = null;
            saveKitToConfig();
            msg(sender, "PvP-Kit gelöscht.");
            return true;
        }

        // /event start <pvp> [name]
        if (args[0].equalsIgnoreCase("start")) {
            if (eventActive) {
                msg(sender, "Es läuft bereits ein Event.");
                return true;
            }
            if (args.length < 2) {
                msg(sender, "Nutzung: /event start <pvp> [name]");
                return true;
            }

            String type = args[1].toLowerCase(Locale.ROOT);
            if (!type.equals("pvp")) {
                msg(sender, "Unbekannter Typ. Verfügbar: pvp");
                return true;
            }

            eventType = EventType.PVP;

            // Name optional
            if (args.length >= 3) {
                eventName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            } else {
                eventName = "PvP";
            }

            if (eventHall == null) {
                msg(sender, "Event-Halle ist nicht gesetzt. (/event sethall)");
                return true;
            }

            if (eventType == EventType.PVP && !kitIsSet()) {
                msg(sender, "PvP-Kit ist nicht gesetzt. (/event setkit)");
                return true;
            }

            eventActive = true;
            participants.clear();
            joinedThisEvent.clear();
            pendingKickOnRespawn.clear();

            broadcast("Event gestartet: " + ChatColor.YELLOW + eventName + ChatColor.WHITE + " - /event join");
            msg(sender, "Event gestartet.");
            return true;
        }

        // /event end
        if (args[0].equalsIgnoreCase("end")) {
            if (!eventActive) {
                msg(sender, "Kein Event aktiv.");
                return true;
            }

            eventActive = false;
            EventType endedType = eventType;
            String ended = eventName;

            eventType = null;
            eventName = null;

            // Restore alle Teilnehmer
            for (UUID id : new HashSet<>(participants)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    restorePlayer(p);
                }
            }

            participants.clear();
            pendingKickOnRespawn.clear();

            broadcast("Event beendet" + (ended != null ? ": " + ChatColor.YELLOW + ended : "") + "!");
            msg(sender, "Event beendet und alle Spieler zurückgesetzt.");
            return true;
        }

        // /event admin ...
        if (args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("event.admin")) {
                msg(sender, "Keine Rechte.");
                return true;
            }

            if (args.length == 1) {
                msg(sender, "Admin-Befehle: /event admin status, /event admin list, /event admin kick <spieler>, /event admin forcejoin <spieler>, /event admin tpall");
                return true;
            }

            String sub = args[1].toLowerCase(Locale.ROOT);

            if (sub.equals("status")) {
                msg(sender, "Event aktiv: " + (eventActive ? ChatColor.GREEN + "JA" : ChatColor.RED + "NEIN"));
                msg(sender, "Typ: " + (eventType != null ? ChatColor.YELLOW + eventType.name() : ChatColor.GRAY + "—"));
                msg(sender, "Name: " + (eventName != null ? ChatColor.YELLOW + eventName : ChatColor.GRAY + "—"));
                msg(sender, "Halle: " + (eventHall != null ? ChatColor.YELLOW + locToString(eventHall) : ChatColor.RED + "nicht gesetzt"));
                msg(sender, "Max: " + ChatColor.YELLOW + maxPlayers + ChatColor.GRAY + " | Teilnehmer: " + ChatColor.YELLOW + participants.size());
                return true;
            }

            if (sub.equals("list")) {
                if (participants.isEmpty()) {
                    msg(sender, "Keine Teilnehmer im Event.");
                    return true;
                }
                msg(sender, "Teilnehmer (" + participants.size() + "):");
                for (UUID id : participants) {
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    if (name == null) name = id.toString();
                    msg(sender, " - " + ChatColor.YELLOW + name);
                }
                return true;
            }

            if (sub.equals("kick")) {
                if (args.length < 3) {
                    msg(sender, "Nutzung: /event admin kick <spieler>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) {
                    msg(sender, "Spieler nicht online.");
                    return true;
                }
                if (!participants.contains(t.getUniqueId())) {
                    msg(sender, "Dieser Spieler ist nicht im Event.");
                    return true;
                }

                restorePlayer(t);
                participants.remove(t.getUniqueId());
                pendingKickOnRespawn.remove(t.getUniqueId());

                msg(sender, "Spieler gekickt: " + ChatColor.YELLOW + t.getName());
                msg(t, "Du wurdest vom Event entfernt.");
                return true;
            }

            if (sub.equals("forcejoin")) {
                if (args.length < 3) {
                    msg(sender, "Nutzung: /event admin forcejoin <spieler>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) {
                    msg(sender, "Spieler nicht online.");
                    return true;
                }
                if (!eventActive || eventType == null) {
                    msg(sender, "Aktuell läuft kein Event.");
                    return true;
                }
                if (eventHall == null) {
                    msg(sender, "Event-Halle ist nicht gesetzt. (/event sethall)");
                    return true;
                }
                if (participants.contains(t.getUniqueId())) {
                    msg(sender, "Spieler ist schon im Event.");
                    return true;
                }
                if (participants.size() >= maxPlayers) {
                    msg(sender, "Das Event ist voll! (" + participants.size() + "/" + maxPlayers + ")");
                    return true;
                }
                if (eventType == EventType.PVP) {
                    if (!kitIsSet()) {
                        msg(sender, "PvP-Kit ist noch nicht gesetzt. (/event setkit)");
                        return true;
                    }
                }

                // Backup von allem + clear
                backupAndClear(t);

                participants.add(t.getUniqueId());
                joinedThisEvent.add(t.getUniqueId()); // bleibt markiert, aber join-sperre wird hier bewusst umgangen

                t.teleport(eventHall);

                if (eventType == EventType.PVP) {
                    giveKit(t);
                    msg(t, "Du hast das PvP-Kit bekommen. Viel Erfolg!");
                }

                msg(sender, "Force-Join: " + ChatColor.YELLOW + t.getName() + ChatColor.GRAY + " (" + participants.size() + "/" + maxPlayers + ")");
                msg(t, "Du wurdest ins Event geholt! (" + participants.size() + "/" + maxPlayers + ")");
                return true;
            }

            if (sub.equals("tpall")) {
                if (eventHall == null) {
                    msg(sender, "Event-Halle ist nicht gesetzt. (/event sethall)");
                    return true;
                }
                int c = 0;
                for (UUID id : participants) {
                    Player t = Bukkit.getPlayer(id);
                    if (t != null && t.isOnline()) {
                        t.teleport(eventHall);
                        c++;
                    }
                }
                msg(sender, "Teleportiert: " + ChatColor.YELLOW + c + ChatColor.GRAY + " Spieler.");
                return true;
            }

            msg(sender, "Unbekannter Admin-Befehl. Nutze: /event admin status|list|kick|forcejoin|tpall");
            return true;
        }

        msg(sender, "Befehle: /event (join), /event leave, /event sethall, /event setmax <zahl>, /event setkit, /event kitclear, /event start <pvp> [name], /event end, /event admin <status|list|kick|forcejoin|tpall>");
        return true;
    }

    // ---------------- TabComplete ----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("event")) return Collections.emptyList();
        boolean admin = sender.hasPermission("event.admin");

        if (args.length == 1) {
            List<String> opts = new ArrayList<>();
            opts.add("join");
            opts.add("leave");

            if (admin) {
                opts.add("sethall");
                opts.add("setmax");
                opts.add("setkit");
                opts.add("kitclear");
                opts.add("admin");

                if (!eventActive) opts.add("start");
                if (eventActive) opts.add("end");
            }
            return filterStartsWith(opts, args[0]);
        }

        if (args.length == 2 && admin) {
            if (args[0].equalsIgnoreCase("setmax")) {
                return filterStartsWith(List.of("5", "10", "16", "20", "32", "50"), args[1]);
            }
            if (args[0].equalsIgnoreCase("start")) {
                return filterStartsWith(List.of("pvp"), args[1]);
            }
            if (args[0].equalsIgnoreCase("admin")) {
                return filterStartsWith(List.of("status", "list", "kick", "forcejoin", "tpall"), args[1]);
            }
        }

        if (args.length == 3 && admin) {
            if (args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("kick") || args[1].equalsIgnoreCase("forcejoin"))) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (args[1].equalsIgnoreCase("kick")) {
                        if (participants.contains(p.getUniqueId())) names.add(p.getName());
                    } else {
                        names.add(p.getName());
                    }
                }
                return filterStartsWith(names, args[2]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterStartsWith(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }

    // ---------------- Event Rules ----------------

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (participants.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) {
            if (participants.contains(p.getUniqueId())) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!participants.contains(p.getUniqueId())) return;

        // Direkt entfernen nach Respawn
        pendingKickOnRespawn.add(p.getUniqueId());
        msg(p, "Du bist gestorben und wirst nach dem Respawn aus dem Event entfernt.");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!pendingKickOnRespawn.remove(p.getUniqueId())) return;

        Bukkit.getScheduler().runTask(this, () -> {
            if (participants.contains(p.getUniqueId())) {
                restorePlayer(p);
                participants.remove(p.getUniqueId());
                msg(p, "Du wurdest aus dem Event entfernt.");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!participants.contains(p.getUniqueId())) return;

        // Beim Quit sauber restoren
        restorePlayer(p);
        participants.remove(p.getUniqueId());
        pendingKickOnRespawn.remove(p.getUniqueId());
    }
}