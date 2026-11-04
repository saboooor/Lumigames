package ca.saboor.lumigames;

import java.io.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

final class Store {
    private final Lumigames plugin;
    final Map<String, Arena> arenas = new LinkedHashMap<>();
    private YamlConfiguration progress;
    Store(Lumigames plugin) { this.plugin = plugin; }
    void load() throws IOException {
        var yaml = DataFiles.read(file("arenas.yml"));
        for (String id : yaml.getKeys(false)) {
            try { arenas.put(id, Arena.load(id, Objects.requireNonNull(yaml.getConfigurationSection(id)))); }
            catch (RuntimeException e) { throw new IOException("Cannot load arena " + id + "; original file preserved", e); }
        }
        progress = DataFiles.read(file("progress.yml"));
    }
    File file(String name) { return new File(plugin.getDataFolder(), name); }
    void saveArenas() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration(); arenas.forEach((id, a) -> a.save(yaml.createSection(id)));
        DataFiles.save(yaml, file("arenas.yml"));
    }
    String path(UUID player, String arena) { return player + "." + arena + "."; }
    String checkpoint(UUID player, String arena) { return progress.getString(path(player, arena) + "checkpoint"); }
    org.bukkit.Location checkpointSpawn(UUID player, String arena) { return progress.getLocation(path(player, arena) + "checkpoint-spawn"); }
    void checkpoint(UUID player, String arena, String checkpoint, long elapsed) throws IOException { checkpoint(player, arena, checkpoint, null, elapsed); }
    void checkpoint(UUID player, String arena, String checkpoint, org.bukkit.Location spawn, long elapsed) throws IOException {
        String path = path(player, arena);
        Object previousSpawn = progress.get(path + "checkpoint-spawn");
        Object previous = progress.get(path + "checkpoint"), previousTime = progress.get(path + "elapsed");
        progress.set(path + "checkpoint-spawn", checkpoint == null ? null : spawn);
        progress.set(path + "checkpoint", checkpoint); progress.set(path + "elapsed", elapsed);
        try { save(); } catch (IOException e) { progress.set(path + "checkpoint-spawn", previousSpawn); progress.set(path + "checkpoint", previous); progress.set(path + "elapsed", previousTime); throw e; }
    }
    long elapsed(UUID player, String arena) { return progress.getLong(path(player, arena) + "elapsed"); }
    long stat(UUID player, String arena, String stat) { return progress.getLong(path(player, arena) + stat); }
    void increment(UUID player, String arena, String stat) { String p = path(player, arena) + stat; progress.set(p, progress.getLong(p) + 1); }
    void win(UUID player, String arena, long millis) throws IOException {
        String p = path(player, arena); increment(player, arena, "wins");
        long best = progress.getLong(p + "best-ms");
        if (best == 0 || millis < best) progress.set(p + "best-ms", Math.max(1, millis));
        progress.set(p + "checkpoint-spawn", null);
        progress.set(p + "checkpoint", null); progress.set(p + "elapsed", null); save();
    }
    List<String> leaderboard(String arena) {
        return progress.getKeys(false).stream().filter(id -> progress.getLong(id + "." + arena + ".wins") > 0)
            .sorted(Comparator.comparingLong((String id) -> progress.getLong(id + "." + arena + ".wins")).reversed())
            .limit(10).map(id -> {
                var p = org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(id));
                return Objects.toString(p.getName(), id.substring(0, 8)) + " · " + progress.getLong(id + "." + arena + ".wins") + " wins";
            }).toList();
    }
    void save() throws IOException { DataFiles.save(progress, file("progress.yml")); }
    void saveAsync() { plugin.saveProgressAsync(progress.saveToString()); }
}
