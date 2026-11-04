package ca.saboor.lumigames;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;

/** Append-before-mutate rollback log, also replayed after an unclean shutdown. */
final class BlockJournal {
    private final File file;
    private final Map<String, String> originals = new LinkedHashMap<>();
    BlockJournal(File file) { this.file = file; }
    void load() throws IOException {
        if (!file.exists()) return;
        for (String line : Files.readAllLines(file.toPath())) {
            if (line.isBlank()) continue;
            String[] parts = line.split(" ", 5);
            if (parts.length != 5) throw new IOException("Invalid rollback entry; preserving " + file);
            originals.putIfAbsent(String.join(" ", Arrays.copyOf(parts, 4)), parts[4]);
        }
    }
    String key(Block b) { return b.getWorld().getUID() + " " + b.getX() + " " + b.getY() + " " + b.getZ(); }
    boolean record(Block b, int limit) throws IOException {
        return record(b, b.getBlockData().getAsString(), limit);
    }
    boolean record(Block b, String data, int limit) throws IOException {
        String key = key(b);
        if (originals.containsKey(key)) return true;
        if (originals.size() >= limit) return false;
        Files.createDirectories(file.toPath().getParent());
        try (var channel = java.nio.channels.FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var bytes = java.nio.charset.StandardCharsets.UTF_8.encode(key + " " + data + "\n");
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(false);
        }
        originals.put(key, data); return true;
    }
    boolean restore() throws IOException {
        boolean complete = true;
        for (var entry : originals.entrySet()) {
            String[] p = entry.getKey().split(" "); World w = Bukkit.getWorld(UUID.fromString(p[0]));
            if (w == null) { complete = false; continue; }
            w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])).setBlockData(Bukkit.createBlockData(entry.getValue()), false);
        }
        if (complete) { Files.deleteIfExists(file.toPath()); originals.clear(); }
        return complete;
    }
    boolean pending() { return !originals.isEmpty(); }
}
