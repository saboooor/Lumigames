package ca.saboor.lumigames;

import java.io.*;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Replace files atomically: never truncate the only copy of a player's inventory. */
final class DataFiles {
    private DataFiles() {}
    static void save(YamlConfiguration yaml, File file) throws IOException {
        saveContent(yaml.saveToString(), file);
    }
    static void saveContent(String content, File file) throws IOException {
        Path target = file.toPath(); Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), file.getName(), ".tmp");
        try {
            Files.writeString(temp, content, java.nio.charset.StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static YamlConfiguration read(File file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) try { yaml.load(file); }
        catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("Invalid YAML: " + file, e); }
        return yaml;
    }
}
