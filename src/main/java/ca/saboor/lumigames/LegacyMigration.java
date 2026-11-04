package ca.saboor.lumigames;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads Addstar Minigames files without loading that plugin or its serialized Java classes. */
final class LegacyMigration {
    record Entry(String source, String original, String id, Arena arena, List<String> warnings) {}
    record Plan(Path source, List<Entry> entries, List<String> warnings) {
        List<Arena> arenas() { return entries.stream().map(Entry::arena).filter(Objects::nonNull).toList(); }
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("Source: " + source);
            lines.add(arenas().size() + " arena(s) ready; " + (entries.size() - arenas().size()) + " skipped. All imports are disabled.");
            lines.addAll(warnings);
            for (Entry entry : entries) {
                lines.add((entry.arena == null ? "SKIP " : "IMPORT ") + entry.original + " -> " + entry.id + " [" + entry.source + "]");
                entry.warnings.forEach(warning -> lines.add("  - " + warning));
            }
            return lines;
        }
    }
    record Result(int imported, Path report, Path backup) {}
    private final Store store;
    private final Function<Map<String, Object>, ItemStack> itemReader;
    LegacyMigration(Store store) { this(store, LegacyMigration::readItem); }
    // The same conversion path can be tested independently of CraftBukkit's item factory.
    LegacyMigration(Store store, Function<Map<String, Object>, ItemStack> itemReader) { this.store = store; this.itemReader = itemReader; }
    Path defaultSource() { return store.file("").toPath().toAbsolutePath().normalize().resolveSibling("Minigames"); }
    Plan preview(Path source) throws IOException {
        source = source.toAbsolutePath().normalize();
        if (!Files.exists(source)) throw new IOException("Minigames source does not exist: " + source);
        List<Path> files = discover(source);
        if (files.isEmpty()) throw new IOException("No legacy arena configs found. Select the old Minigames data directory or a single arena YAML file.");
        List<Entry> entries = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Set<String> occupied = new HashSet<>(store.arenas.keySet());
        Map<String, Object> global = Map.of();
        Path globalFile = source.resolve("config.yml");
        if (Files.isDirectory(source) && Files.isRegularFile(globalFile)) {
            try { global = readYaml(globalFile); }
            catch (IOException e) { warnings.add("Global config could not be read; arena defaults will be used."); }
        }
        for (Path file : files) {
            Map<String, Object> root;
            try { root = readYaml(file); }
            catch (IOException e) { entries.add(new Entry(file.toString(), file.getFileName().toString(), "", null, List.of(e.getMessage()))); continue; }
            boolean found = false;
            for (var pair : root.entrySet()) {
                Map<String, Object> data = map(pair.getValue());
                if (!looksLikeArena(data)) continue;
                found = true; String id = normalizeId(pair.getKey()); List<String> notes = new ArrayList<>(); Arena arena = null;
                if (!id.equals(pair.getKey())) notes.add("Arena ID normalized; update signs and permissions to use " + id + ".");
                if (occupied.contains(id)) notes.add("Existing or earlier imported arena ID; skipped without overwriting.");
                else {
                    try {
                        arena = convert(id, data, global, notes);
                        notes.addAll(arena.problems());
                        occupied.add(id);
                    } catch (RuntimeException e) { arena = null; notes.add("Cannot convert: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()) + ". Source preserved; fix the source or load its worlds and retry."); }
                }
                entries.add(new Entry(file.toString(), pair.getKey(), id, arena, List.copyOf(notes)));
            }
            if (!found) entries.add(new Entry(file.toString(), file.getFileName().toString(), "", null, List.of("No recognizable legacy arena definitions in this file.")));
            if (file.getFileName().toString().equals("config.yml")) {
                try (var siblings = Files.list(file.getParent())) {
                    List<String> extra = siblings.filter(p -> !p.equals(file) && p.getFileName().toString().endsWith(".yml")).map(p -> p.getFileName().toString()).sorted().toList();
                    if (!extra.isEmpty()) warnings.add("Separate module files not imported from " + file.getParent() + ": " + String.join(", ", extra));
                }
            }
        }
        warnings.add("World signs, player checkpoints/inventory recovery, statistics databases, global server policies and region scripts are not modified.");
        return new Plan(source, List.copyOf(entries), List.copyOf(warnings));
    }
    /** Commit new IDs only. Back up exact existing bytes and write the report before changing arenas.yml. */
    Result apply(Plan plan) throws IOException {
        List<Arena> additions = plan.arenas();
        if (additions.isEmpty()) throw new IllegalArgumentException("No new arenas can be imported; review the preview warnings.");
        for (Arena arena : additions) if (store.arenas.containsKey(arena.id)) throw new IllegalArgumentException("Arena " + arena.id + " now exists; preview again.");
        Path dir = store.file("migration/" + java.time.Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8)).toPath();
        Files.createDirectories(dir);
        Path current = store.file("arenas.yml").toPath(); Path backup = dir.resolve("arenas.before.yml");
        if (Files.exists(current) && !Files.isRegularFile(current)) throw new IOException("Arena destination is not a regular file: " + current);
        if (Files.exists(current)) Files.copy(current, backup);
        else DataFiles.save(new YamlConfiguration(), backup.toFile());
        YamlConfiguration report = new YamlConfiguration(); report.set("source", plan.source.toString()); report.set("report", plan.lines());
        report.set("backup", backup.toString());
        DataFiles.save(report, dir.resolve("report.yml").toFile());
        YamlConfiguration merged = new YamlConfiguration();
        store.arenas.forEach((id, arena) -> arena.save(merged.createSection(id)));
        for (Arena arena : additions) { arena.enabled = false; arena.save(merged.createSection(arena.id)); }
        DataFiles.save(merged, current.toFile());
        for (Arena arena : additions) store.arenas.put(arena.id, arena);
        return new Result(additions.size(), dir.resolve("report.yml"), backup);
    }
    static List<Path> discover(Path source) throws IOException {
        if (Files.isRegularFile(source)) return List.of(source);
        Path directory = Files.isDirectory(source.resolve("minigames")) ? source.resolve("minigames") : source;
        List<Path> files = new ArrayList<>();
        try (var children = Files.list(directory)) {
            for (Path child : children.sorted().toList()) {
                if (Files.isSymbolicLink(child)) continue;
                if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("config.yml"))) files.add(child.resolve("config.yml"));
                else if (Files.isRegularFile(child) && (child.getFileName().toString().endsWith(".yml") || child.getFileName().toString().endsWith(".yaml"))
                    && (!directory.equals(source) || !Set.of("config.yml", "messages.yml", "signs.yml", "loadouts.yml", "treasure.yml").contains(child.getFileName().toString()))) files.add(child);
            }
        }
        // An explicitly selected arena directory may contain its own config.yml.
        if (files.isEmpty() && Files.isRegularFile(source.resolve("config.yml"))) files.add(source.resolve("config.yml"));
        return files;
    }
    static Map<String, Object> readYaml(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(20); options.setCodePointLimit(8 * 1024 * 1024);
        try (Reader reader = Files.newBufferedReader(file)) {
            Object raw = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(raw instanceof Map<?, ?>)) throw new IOException("Expected a YAML mapping: " + file);
            return map(raw);
        } catch (org.yaml.snakeyaml.error.YAMLException e) { throw new IOException("Invalid legacy YAML in " + file.getFileName() + ": " + e.getMessage(), e); }
    }
    static boolean looksLikeArena(Map<String, Object> data) { return data.containsKey("type") || data.containsKey("scoretype") || data.containsKey("startpos") || data.containsKey("regenRegions"); }
    static String normalizeId(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (id.isEmpty()) id = "imported";
        return id.substring(0, Math.min(32, id.length()));
    }
    Arena convert(String id, Map<String, Object> c, Map<String, Object> global, List<String> notes) {
        Arena a = new Arena(id, mode(c, notes));
        a.name = text(c, "displayName", id); a.description = text(c, "objective", "Imported from Minigames. Review arena setup before enabling.");
        a.enabled = false;
        if (flag(c, "enabled", false)) notes.add("Legacy enabled state intentionally reset; review then enable this arena.");
        a.maxPlayers = bounded(c, "maxplayers", 4, 1, 100, notes);
        if (!a.solo()) a.maxPlayers = Math.max(2, a.maxPlayers);
        a.minPlayers = a.solo() ? 1 : bounded(c, "minplayers", 2, 2, a.maxPlayers, notes);
        if (a.solo() && !flag(c, "spMaxPlayers", false)) { a.maxPlayers = 100; notes.add("Uncapped legacy solo capacity mapped to Lumigames' maximum of 100."); }
        if (number(c, "timer", 0) <= 0) { a.duration = 0; notes.add("Legacy unlimited/missing timer preserved as no time limit (duration 0)."); }
        else a.duration = bounded(c, "timer", 300, 1, 86400, notes);
        Map<String, Object> multiplayer = map(global.get("multiplayer"));
        int startWait = number(c, "startWaitTime", 0);
        if (startWait <= 0) { startWait = number(multiplayer, "startcountdown", 5); if (startWait <= 0) startWait = 5; }
        int combined = Math.addExact(Math.max(0, number(multiplayer, "waitforplayers", 10)), startWait);
        a.countdown = Math.min(300, combined);
        notes.add("Lobby wait and start countdown combined into one " + a.countdown + " second countdown.");
        a.lives = bounded(c, "lives", 1, 1, 100, notes);
        a.target = bounded(c, "maxscore", number(c, "minscore", 5), 1, 10000, notes);
        if (c.containsKey("minscore") && c.containsKey("maxscore") && number(c, "minscore", 5) != number(c, "maxscore", 5)) notes.add("Random score range replaced with the maxscore target.");
        a.breakBlocks = flag(c, "blockbreak", a.breakBlocks); a.placeBlocks = flag(c, "blockplace", false);
        a.flight = flag(c, "allowFlight", false) || flag(c, "enableFlight", false);
        a.paintball = flag(c, "paintball", false); a.infiniteAmmo = flag(c, "unlimitedammo", false);
        a.requirePermission = flag(c, "usepermissions", false);
        if (a.requirePermission) notes.add("Assign the new permission lumigames.arena." + id + "; legacy permission nodes are not reused.");
        a.lateJoin = flag(c, "latejoin", false);
        if (a.lateJoin && Set.of(Arena.Mode.RACE, Arena.Mode.LIVES, Arena.Mode.SPLEEF, Arena.Mode.INFECTION).contains(a.mode)) { a.lateJoin = false; notes.add("Late joining disabled for objective fairness."); }
        a.start = firstLocation(c.get("startpos"), "startpos", notes);
        a.lobby = location(c.get("lobbypos"), "lobbypos", notes); a.exit = location(c.get("quitpos"), "quitpos", notes);
        a.spectator = location(c.get("spectatorpos"), "spectatorpos", notes);
        Map<String, Object> teams = map(c.get("teams"));
        a.teams = a.teams || !teams.isEmpty();
        for (var team : teams.entrySet()) {
            String color = team.getKey().toLowerCase(Locale.ROOT);
            if (!color.equals("red") && !color.equals("blue")) { notes.add("Team " + team.getKey() + " is unsupported; only red/blue teams exist. Reconfigure teams manually."); continue; }
            Location spawn = firstLocation(map(team.getValue()).get("startpos"), "teams." + team.getKey() + ".startpos", notes);
            if (color.equals("red")) a.redSpawn = spawn; else a.blueSpawn = spawn;
            if (map(team.getValue()).containsKey("displayName")) notes.add("Team " + color + " uses Lumigames' fixed name and balancing rules.");
        }
        if (a.start == null) a.start = a.redSpawn != null ? a.redSpawn.clone() : a.blueSpawn == null ? null : a.blueSpawn.clone();
        bounds(a, c, notes);
        loadouts(a, c, notes);
        if (c.containsKey("endpos")) notes.add("endpos is a post-win teleport in Minigames; it was not used as a finish trigger or quit location. Place a Lumigames finish sign/plate.");
        if (a.mode == Arena.Mode.SOLO || a.mode == Arena.Mode.RACE) notes.add("Checkpoint and finish signs/plates must be configured manually; arena configs do not contain the legacy world signs or player checkpoints.");
        if (a.mode == Arena.Mode.CTF) notes.add("Set red-flag and blue-flag manually; legacy flag signs are stored in the world.");
        if (a.mode == Arena.Mode.TREASURE) notes.add("Legacy randomized/global treasure spawning has no direct equivalent; add fixed treasure blocks manually.");
        if (a.breakBlocks || a.placeBlocks) notes.add("Lumigames restricts placed blocks and containers; block whitelists and floor degeneration do not transfer.");
        if (c.containsKey("sfloor") || c.containsKey("sfloorpos")) notes.add("Automatic floor degeneration is not imported.");
        if (a.paintball) notes.add("Paintball uses lethal hits; legacy paintballdmg does not transfer.");
        Set<String> handled = Set.of("type", "scoretype", "displayName", "objective", "enabled", "maxplayers", "minplayers", "spMaxPlayers", "timer", "startWaitTime", "lives", "minscore", "maxscore", "blockbreak", "blockplace", "allowFlight", "enableFlight", "paintball", "unlimitedammo", "usepermissions", "latejoin", "startpos", "lobbypos", "quitpos", "spectatorpos", "teams", "regenRegions", "regenarea", "loadouts", "loadout", "extraloadouts", "endpos", "sfloor", "sfloorpos", "whitelistblocks", "whitelistmode");
        List<String> unknown = c.keySet().stream().filter(key -> !handled.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) notes.add("Settings without an exact mapping (review original): " + String.join(", ", unknown));
        notes.add("Legacy rewards/betting/scripts are not enabled or executed; configure winner commands manually.");
        return a;
    }
    static Arena.Mode mode(Map<String, Object> c, List<String> notes) {
        String mechanic = text(c, "scoretype", "custom").toLowerCase(Locale.ROOT), type = text(c, "type", "SINGLEPLAYER").toUpperCase(Locale.ROOT);
        if (mechanic.equals("treasurehunt")) return Arena.Mode.TREASURE;
        if (type.equals("SINGLEPLAYER")) return Arena.Mode.SOLO;
        if (!type.equals("MULTIPLAYER")) throw new IllegalArgumentException("Unsupported game type " + type);
        return switch (mechanic) {
            case "kills" -> Arena.Mode.KILLS; case "ctf" -> Arena.Mode.CTF; case "infection" -> Arena.Mode.INFECTION;
            case "juggernaut" -> Arena.Mode.JUGGERNAUT; case "lives" -> isSpleef(c) ? Arena.Mode.SPLEEF : Arena.Mode.LIVES;
            case "custom" -> isSpleef(c) ? Arena.Mode.SPLEEF : number(c, "lives", 0) > 0 ? Arena.Mode.LIVES : Arena.Mode.CUSTOM;
            default -> { notes.add("Unknown mechanic " + mechanic + " mapped to CUSTOM; implement its objective manually."); yield Arena.Mode.CUSTOM; }
        };
    }
    static boolean isSpleef(Map<String, Object> c) {
        return c.containsKey("sfloor") || c.containsKey("sfloorpos") || c.get("whitelistblocks") instanceof List<?> blocks && blocks.contains("SNOW_BLOCK");
    }
    void bounds(Arena a, Map<String, Object> c, List<String> notes) {
        List<Location[]> regions = new ArrayList<>();
        for (var entry : map(c.get("regenRegions")).entrySet()) {
            Map<String, Object> region = map(entry.getValue());
            Location first = vector(region.get("pos1"), region.get("world"), "regenRegions." + entry.getKey() + ".pos1", notes);
            Location second = vector(region.get("pos2"), region.get("world"), "regenRegions." + entry.getKey() + ".pos2", notes);
            if (first != null && second != null) regions.add(new Location[]{first, second});
        }
        Map<String, Object> legacy = map(c.get("regenarea"));
        if (regions.isEmpty() && !legacy.isEmpty()) {
            Location first = location(legacy.get("1"), "regenarea.1", notes), second = location(legacy.get("2"), "regenarea.2", notes);
            if (first != null && second != null) regions.add(new Location[]{first, second});
        }
        if (regions.isEmpty()) { notes.add("No regeneration bounds found; set corner1/corner2 manually. Floor-degenerator bounds are not playable arena bounds."); return; }
        World world = regions.getFirst()[0].getWorld();
        if (regions.stream().flatMap(Arrays::stream).anyMatch(l -> !Objects.equals(world, l.getWorld()))) { notes.add("Regeneration regions span different worlds; set arena corners manually."); return; }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Location[] pair : regions) for (Location p : pair) { minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ()); maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ()); }
        a.corner1 = new Location(world, minX, minY, minZ); a.corner2 = new Location(world, maxX, maxY, maxZ);
        notes.add("Arena boundaries inferred from " + regions.size() + " regeneration region(s); verify the playable area" + (regions.size() > 1 ? "; the enclosing cuboid also includes gaps between regions." : "."));
    }
    Location firstLocation(Object raw, String path, List<String> notes) {
        Map<String, Object> value = map(raw);
        if (value.containsKey("world")) return location(value, path, notes);
        List<Object> locations = raw instanceof List<?> list ? new ArrayList<>(list) : value.entrySet().stream().sorted((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey())); } catch (NumberFormatException e) { return a.getKey().compareTo(b.getKey()); }
        }).map(Map.Entry::getValue).toList();
        if (locations.size() > 1) notes.add(path + ": only the first start position is supported.");
        return locations.isEmpty() ? null : location(locations.getFirst(), path, notes);
    }
    Location vector(Object raw, Object world, String path, List<String> notes) {
        if (!(raw instanceof String text)) { if (raw != null) notes.add(path + ": unsupported region coordinates."); return null; }
        String[] p = text.split(":"); if (p.length != 3) throw new IllegalArgumentException("Invalid region coordinates at " + path);
        return location(Map.of("world", Objects.toString(world, ""), "x", Double.parseDouble(p[0]), "y", Double.parseDouble(p[1]), "z", Double.parseDouble(p[2])), path, notes);
    }
    Location location(Object raw, String path, List<String> notes) {
        if (raw == null) return null;
        Map<String, Object> value = map(raw);
        if (!(value.get("world") instanceof String name) || !value.containsKey("x") || !value.containsKey("y") || !value.containsKey("z")) throw new IllegalArgumentException("Incomplete location at " + path);
        World world = Bukkit.getWorld(name);
        if (world == null) try { world = Bukkit.getWorld(UUID.fromString(name)); } catch (IllegalArgumentException ignored) {}
        if (world == null) throw new IllegalArgumentException("World '" + name + "' at " + path + " is not loaded");
        return new Location(world, decimal(value.get("x")), decimal(value.get("y")), decimal(value.get("z")), (float)decimal(value.getOrDefault("yaw", 0)), (float)decimal(value.getOrDefault("pitch", 0)));
    }
    void loadouts(Arena arena, Map<String, Object> c, List<String> notes) {
        Map<String, Object> kits = new LinkedHashMap<>();
        if (c.containsKey("loadout")) kits.put("default", c.get("loadout"));
        kits.putAll(map(c.get("extraloadouts"))); kits.putAll(map(c.get("loadouts")));
        Set<String> used = new HashSet<>();
        for (var entry : kits.entrySet()) {
            String id = normalizeId(entry.getKey());
            if (!used.add(id)) { notes.add("Loadout ID collision for " + entry.getKey() + "; skipped."); continue; }
            if (!id.equals(entry.getKey())) notes.add("Loadout " + entry.getKey() + " renamed to " + id + ".");
            Map<String, Object> kit = map(entry.getValue()), items = kit.containsKey("items") ? map(kit.get("items")) : kit;
            ItemStack[] contents = new ItemStack[41];
            for (var item : items.entrySet()) {
                int oldSlot;
                try { oldSlot = Integer.parseInt(item.getKey()); } catch (NumberFormatException ignored) { continue; }
                int slot = slot(oldSlot);
                if (slot < 0) { notes.add("Loadout " + id + ": unsupported slot " + oldSlot + "."); continue; }
                try {
                    contents[slot] = itemReader.apply(map(item.getValue()));
                    if (contents[slot] == null) throw new IllegalArgumentException("empty item");
                } catch (RuntimeException e) { throw new IllegalArgumentException("Loadout " + id + " item in slot " + oldSlot + " could not be converted: " + e.getMessage(), e); }
            }
            List<PotionEffect> effects = new ArrayList<>();
            for (var effect : map(kit.get("potions")).entrySet()) {
                PotionEffectType type = PotionEffectType.getByName(effect.getKey());
                if (type == null) { notes.add("Loadout " + id + ": unknown potion effect " + effect.getKey() + " skipped."); continue; }
                Map<String, Object> settings = map(effect.getValue());
                effects.add(new PotionEffect(type, Math.max(1, number(settings, "dur", 200)), Math.max(0, Math.min(255, number(settings, "amp", 0)))));
            }
            arena.kits.put(id, contents); arena.kitEffects.put(id, effects);
            if (id.equals("default")) arena.loadout = contents;
            List<String> unsupported = kit.keySet().stream().filter(key -> !key.matches("-?\\d+") && !Set.of("items", "potions").contains(key)).sorted().toList();
            if (!unsupported.isEmpty()) notes.add("Loadout " + id + " settings not transferred: " + String.join(", ", unsupported));
        }
    }
    static int slot(int old) { return old == -106 ? 40 : old >= 100 && old <= 103 ? old - 64 : old >= 0 && old <= 40 ? old : -1; }
    static ItemStack readItem(Map<String, Object> raw) {
        if (raw.isEmpty()) throw new IllegalArgumentException("Missing item data");
        Map<String, Object> item = new LinkedHashMap<>(raw); item.remove("==");
        if (item.get("meta") instanceof Map<?, ?> meta) {
            Map<String, Object> data = map(meta);
            // Only Paper's built-in ItemMeta deserializer is allowed; never invoke legacy plugin classes.
            if (!Objects.equals(data.get("=="), "ItemMeta")) throw new IllegalArgumentException("Unsupported item metadata type");
            item.put("meta", decodeMetadata(data));
        }
        return ItemStack.deserialize(item);
    }
    static Object decodeMetadata(Object raw) {
        if (raw instanceof List<?> list) return list.stream().map(LegacyMigration::decodeMetadata).toList();
        if (!(raw instanceof Map<?, ?>)) return raw;
        Map<String, Object> result = new LinkedHashMap<>();
        map(raw).forEach((key, value) -> result.put(key, decodeMetadata(value)));
        if (!result.containsKey("==")) return result;
        String alias = Objects.toString(result.get("=="));
        Set<String> allowed = Set.of("ItemMeta", "org.bukkit.inventory.ItemStack", "Color", "org.bukkit.Color", "PotionEffect", "org.bukkit.potion.PotionEffect",
            "Firework", "FireworkEffect", "org.bukkit.FireworkEffect", "Pattern", "org.bukkit.block.banner.Pattern", "AttributeModifier", "org.bukkit.attribute.AttributeModifier",
            "Vector", "org.bukkit.util.Vector", "BlockVector", "org.bukkit.util.BlockVector");
        if (!allowed.contains(alias)) throw new IllegalArgumentException("Unsupported item metadata class " + alias);
        Object decoded = ConfigurationSerialization.deserializeObject(result);
        if (decoded == null) throw new IllegalArgumentException("Could not decode item metadata class " + alias);
        return decoded;
    }
    static Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
    static String text(Map<String, Object> c, String key, String fallback) { return Objects.toString(c.get(key), fallback); }
    static boolean flag(Map<String, Object> c, String key, boolean fallback) { return c.get(key) instanceof Boolean b ? b : fallback; }
    static int number(Map<String, Object> c, String key, int fallback) {
        Object raw = c.get(key); if (raw == null) return fallback;
        double number = decimal(raw);
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid integer " + key);
        return (int)number;
    }
    static int bounded(Map<String, Object> c, String key, int fallback, int min, int max, List<String> notes) {
        int value = number(c, key, fallback), result = Math.max(min, Math.min(max, value));
        if (value != result) notes.add(key + "=" + value + " adjusted to " + result + " for Lumigames limits.");
        return result;
    }
    static double decimal(Object raw) {
        double value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite location or rule value");
        return value;
    }
}
