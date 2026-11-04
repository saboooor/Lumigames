package ca.saboor.lumigames;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** Persisted arena definition. Runtime match state lives in Match. */
final class Arena {
    enum Mode { SOLO, RACE, KILLS, LIVES, SPLEEF, CTF, INFECTION, JUGGERNAUT, TREASURE, CUSTOM }
    final String id;
    String name, description = "Reach the finish!";
    Mode mode;
    boolean enabled, teams, breakBlocks, placeBlocks, lateJoin, pvp, flight, paintball, infiniteAmmo, requirePermission;
    final Map<String, ItemStack[]> kits = new LinkedHashMap<>();
    final Map<String, List<org.bukkit.potion.PotionEffect>> kitEffects = new LinkedHashMap<>();
    int minPlayers = 2, maxPlayers = 16, countdown = 10, duration = 300, lives = 1, target = 5;
    Location start, lobby, exit, spectator, corner1, corner2, redSpawn, blueSpawn, redFlag, blueFlag;
    final Map<String, List<Location>> checkpointPlates = new LinkedHashMap<>();
    final Map<String, Location> checkpoints = new LinkedHashMap<>();
    final List<Location> treasures = new ArrayList<>();
    final List<String> rewards = new ArrayList<>();
    ItemStack[] loadout = new ItemStack[41];
    Arena(String id, Mode mode) {
        if (!validId(id)) throw new IllegalArgumentException("Use 1–32 lowercase letters, digits, underscores or hyphens for IDs.");
        this.id = id; this.name = id; this.mode = mode;
        if (mode == Mode.SOLO || mode == Mode.TREASURE) minPlayers = 1;
        pvp = switch (mode) { case KILLS, LIVES, CTF, INFECTION, JUGGERNAUT -> true; default -> false; };
        teams = mode == Mode.CTF || mode == Mode.INFECTION;
        breakBlocks = mode == Mode.SPLEEF;
    }
    static boolean validId(String value) { return value != null && value.matches("[a-z0-9_-]{1,32}"); }
    boolean timed() { return duration > 0; }
    String timeLimitLabel() { return timed() ? duration + "s time limit" : "No time limit"; }
    boolean solo() { return mode == Mode.SOLO || mode == Mode.TREASURE; }
    boolean inside(Location l) {
        if (corner1 == null || corner2 == null || l.getWorld() == null || !l.getWorld().equals(corner1.getWorld())) return false;
        return l.getX() >= Math.min(corner1.getBlockX(), corner2.getBlockX()) && l.getX() < Math.max(corner1.getBlockX(), corner2.getBlockX()) + 1
            && l.getY() >= Math.min(corner1.getBlockY(), corner2.getBlockY()) && l.getY() < Math.max(corner1.getBlockY(), corner2.getBlockY()) + 1
            && l.getZ() >= Math.min(corner1.getBlockZ(), corner2.getBlockZ()) && l.getZ() < Math.max(corner1.getBlockZ(), corner2.getBlockZ()) + 1;
    }
    static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && Objects.equals(a.getWorld(), b.getWorld()) && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
    static boolean pressurePlate(Material material) { return material.name().endsWith("_PRESSURE_PLATE"); }
    record CheckpointPlate(String id, Location spawn) {}
    CheckpointPlate checkpointAt(Location location) {
        for (var entry : checkpoints.entrySet()) {
            List<Location> plates = checkpointPlates.get(entry.getKey());
            if (plates == null) {
                if (sameBlock(location, entry.getValue())) return new CheckpointPlate(entry.getKey(), entry.getValue().clone());
            } else for (Location plate : plates) if (sameBlock(location, plate)) return new CheckpointPlate(entry.getKey(), plate.clone());
        }
        return null;
    }
    Location checkpointSpawn(String id, Location saved) {
        Location fallback = checkpoints.get(id);
        if (fallback == null) return null;
        if (saved != null) {
            for (Location plate : checkpointPlates.getOrDefault(id, List.of())) if (sameBlock(saved, plate)) return plate.clone();
        }
        return fallback.clone();
    }
    List<String> problems() {
        List<String> errors = new ArrayList<>();
        if (start == null || start.getWorld() == null) errors.add("Set a start in a loaded world.");
        if (corner1 == null || corner2 == null || corner1.getWorld() == null || !corner1.getWorld().equals(corner2.getWorld())) errors.add("Set both arena corners in the same loaded world. Stand at one corner and run /lg admin set " + id + " corner1, then move to the opposite corner in the same world and run /lg admin set " + id + " corner2. Include the full playable height, start and lobby inside the boundaries. Then run /lg admin enable " + id + ".");
        else if (start != null && !inside(start)) errors.add("Start must be inside the arena.");
        if (!solo() && minPlayers < 2) errors.add("Multiplayer games require at least two players.");
        if (spectator != null && !inside(spectator)) errors.add("Spectator spawn must be inside the arena.");
        if (exit != null && inside(exit)) errors.add("Exit must be outside the arena.");
        if (lateJoin && (mode == Mode.INFECTION || mode == Mode.LIVES || mode == Mode.SPLEEF || mode == Mode.RACE)) errors.add("Late joining is incompatible with this mode.");
        if (!solo() && (lobby == null || !inside(lobby))) errors.add("Set a lobby inside the arena.");
        if (teams && (redSpawn == null || blueSpawn == null || !inside(redSpawn) || !inside(blueSpawn))) errors.add("Set red and blue spawns inside the arena.");
        if (mode == Mode.CTF && (redFlag == null || blueFlag == null || !inside(redFlag) || !inside(blueFlag))) errors.add("Set red and blue flags inside the arena.");
        if (mode == Mode.TREASURE && treasures.isEmpty()) errors.add("Add at least one treasure location.");
        if (minPlayers < 1 || maxPlayers < minPlayers || maxPlayers > 100 || countdown < 0 || duration < 0 || lives < 1 || target < 1) errors.add("Invalid player counts, timers, lives or target.");
        for (var entry : checkpointPlates.entrySet()) {
            if (!checkpoints.containsKey(entry.getKey())) errors.add("Linked plates must refer to an existing checkpoint.");
            for (Location plate : entry.getValue()) if (!inside(plate)) errors.add("All linked checkpoint plates must be inside the arena.");
        }
        for (Location cp : checkpoints.values()) if (!inside(cp)) errors.add("All checkpoints must be inside the arena.");
        for (Location treasure : treasures) if (!inside(treasure)) errors.add("All treasures must be inside the arena.");
        return errors;
    }
    void save(ConfigurationSection c) {
        c.set("flight", flight); c.set("paintball", paintball); c.set("infinite-ammo", infiniteAmmo); c.set("require-permission", requirePermission);
        c.set("kits", null);
        kits.forEach((id, items) -> { c.set("kits." + id + ".items", Arrays.asList(items)); c.set("kits." + id + ".effects", kitEffects.getOrDefault(id, List.of())); });
        c.set("name", name); c.set("description", description); c.set("mode", mode.name());
        c.set("enabled", enabled); c.set("teams", teams); c.set("pvp", pvp);
        c.set("break-blocks", breakBlocks); c.set("place-blocks", placeBlocks); c.set("late-join", lateJoin);
        c.set("min-players", minPlayers); c.set("max-players", maxPlayers); c.set("countdown", countdown);
        c.set("duration", duration); c.set("lives", lives); c.set("target", target);
        c.set("start", start); c.set("lobby", lobby); c.set("exit", exit); c.set("spectator", spectator);
        c.set("corner1", corner1); c.set("corner2", corner2); c.set("red-spawn", redSpawn); c.set("blue-spawn", blueSpawn);
        c.set("red-flag", redFlag); c.set("blue-flag", blueFlag);
        c.set("checkpoints", null); checkpoints.forEach((key, value) -> c.set("checkpoints." + key, value));
        c.set("checkpoint-plates", null); checkpointPlates.forEach((id, plates) -> c.set("checkpoint-plates." + id, plates));
        c.set("treasures", treasures); c.set("rewards", rewards); c.set("loadout", Arrays.asList(loadout));
    }
    static Arena load(String id, ConfigurationSection c) {
        Arena a = new Arena(id, Mode.valueOf(c.getString("mode", "SOLO")));
        a.name = c.getString("name", id); a.description = c.getString("description", "");
        a.flight = c.getBoolean("flight"); a.paintball = c.getBoolean("paintball"); a.infiniteAmmo = c.getBoolean("infinite-ammo"); a.requirePermission = c.getBoolean("require-permission");
        var kits = c.getConfigurationSection("kits");
        if (kits != null) for (String key : kits.getKeys(false)) {
            ItemStack[] items = new ItemStack[41]; List<?> values = kits.getList(key + ".items", List.of());
            for (int i = 0; i < Math.min(41, values.size()); i++) if (values.get(i) instanceof ItemStack item) items[i] = item;
            a.kits.put(key, items); List<org.bukkit.potion.PotionEffect> effects = new ArrayList<>();
            for (Object o : kits.getList(key + ".effects", List.of())) if (o instanceof org.bukkit.potion.PotionEffect effect) effects.add(effect);
            a.kitEffects.put(key, effects);
        }
        a.enabled = c.getBoolean("enabled"); a.teams = c.getBoolean("teams", a.teams); a.pvp = c.getBoolean("pvp", a.pvp);
        a.breakBlocks = c.getBoolean("break-blocks", a.breakBlocks); a.placeBlocks = c.getBoolean("place-blocks"); a.lateJoin = c.getBoolean("late-join");
        a.minPlayers = c.getInt("min-players", a.minPlayers); a.maxPlayers = c.getInt("max-players", 16);
        a.countdown = c.getInt("countdown", 10); a.duration = c.getInt("duration", 300); a.lives = c.getInt("lives", 1); a.target = c.getInt("target", 5);
        a.start = c.getLocation("start"); a.lobby = c.getLocation("lobby"); a.exit = c.getLocation("exit"); a.spectator = c.getLocation("spectator");
        a.corner1 = c.getLocation("corner1"); a.corner2 = c.getLocation("corner2"); a.redSpawn = c.getLocation("red-spawn"); a.blueSpawn = c.getLocation("blue-spawn");
        a.redFlag = c.getLocation("red-flag"); a.blueFlag = c.getLocation("blue-flag");
        var cps = c.getConfigurationSection("checkpoints");
        if (cps != null) for (String key : cps.getKeys(false)) { Location l = cps.getLocation(key); if (l != null) a.checkpoints.put(key, l); }
        var plates = c.getConfigurationSection("checkpoint-plates");
        if (plates != null) for (String key : plates.getKeys(false)) {
            List<Location> locations = new ArrayList<>();
            for (Object raw : plates.getList(key, List.of())) if (raw instanceof Location location) locations.add(location);
            a.checkpointPlates.put(key, locations);
        }
        for (Object o : c.getList("treasures", List.of())) if (o instanceof Location l) a.treasures.add(l);
        a.rewards.addAll(c.getStringList("rewards"));
        List<?> kit = c.getList("loadout", List.of());
        for (int i = 0; i < Math.min(41, kit.size()); i++) if (kit.get(i) instanceof ItemStack item) a.loadout[i] = item;
        return a;
    }
}
