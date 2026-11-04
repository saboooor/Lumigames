package ca.saboor.lumigames;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class Lumigames extends JavaPlugin {
    private ExecutorService saveExecutor;
    Store store;
    GameEngine engine;
    Menus menus;
    GameCommands commands;
    @Override public void onEnable() {
        saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Lumigames-data-save");
            thread.setDaemon(true);
            return thread;
        });
        saveDefaultConfig(); store = new Store(this); engine = new GameEngine(this, store);
        try { store.load(); engine.recoverBlocks(); }
        catch (Exception e) { getLogger().log(Level.SEVERE, "Data could not be loaded. Original files are preserved; disabling to avoid data loss.", e); getServer().getPluginManager().disablePlugin(this); return; }
        menus = new Menus(this); commands = new GameCommands(this);
        Objects.requireNonNull(getCommand("lumigames")).setExecutor(commands);
        Objects.requireNonNull(getCommand("lumigames")).setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getScheduler().runTaskTimer(this, engine::tick, 20, 20);
        long interval = Math.max(10, getConfig().getLong("autosave-seconds", 60)) * 20;
        getServer().getScheduler().runTaskTimer(this, () -> store.saveAsync(), interval, interval);
        Bukkit.getOnlinePlayers().forEach(engine::recover);
    }
    @Override public void onDisable() {
        if (engine != null) for (Player p : Bukkit.getOnlinePlayers()) if (engine.session(p) != null) engine.disconnect(p);
        if (saveExecutor != null) {
            saveExecutor.shutdown();
            try {
                if (!saveExecutor.awaitTermination(30, TimeUnit.SECONDS)) getLogger().severe("Timed out waiting for progress saves.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                getLogger().log(Level.SEVERE, "Interrupted while waiting for progress saves.", e);
            }
        }
        if (store != null) try { store.save(); } catch (Exception e) { getLogger().log(Level.SEVERE, "Could not save progress", e); }
    }
    void saveProgressAsync(String content) {
        if (saveExecutor == null || saveExecutor.isShutdown()) {
            getLogger().warning("Progress save skipped because the save queue is stopped.");
            return;
        }
        saveExecutor.execute(() -> {
            try { DataFiles.saveContent(content, store.file("progress.yml")); }
            catch (IOException e) { getLogger().log(Level.SEVERE, "Progress save failed", e); }
        });
    }
    void sync(Runnable action) { if (isEnabled()) getServer().getScheduler().runTask(this, () -> { if (isEnabled()) action.run(); }); }
    void message(Player p, String message) { p.sendMessage(Component.text(getConfig().getString("prefix", "Lumigames » "), NamedTextColor.AQUA).append(Component.text(message, NamedTextColor.GRAY))); }
    void error(Player p, String message) { p.sendMessage(Component.text(message, NamedTextColor.RED)); }
    void failure(Player p, String message, Exception e) { error(p, message + "."); getLogger().log(Level.SEVERE, message, e); }
    boolean recordReplacement(Block block, BlockState old, Arena arena) {
        if (old instanceof TileState) return false;
        try { return engine.journals.computeIfAbsent(arena.id, id -> new BlockJournal(store.file("rollback/" + id + ".log"))).record(block, old.getBlockData().getAsString(), getConfig().getInt("max-recorded-blocks", 50000)); }
        catch (IOException e) { getLogger().log(Level.SEVERE, "Cannot journal placed block", e); return false; }
    }
}
