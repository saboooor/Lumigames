package ca.saboor.lumigames;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

final class GameEngine {
    final Lumigames plugin;
    final Store store;
    final Map<UUID, Session> sessions = new HashMap<>();
    final Map<String, Match> matches = new HashMap<>();
    final Map<String, BlockJournal> journals = new HashMap<>();
    final Set<UUID> internalTeleport = new HashSet<>();
    GameEngine(Lumigames plugin, Store store) { this.plugin = plugin; this.store = store; }
    File recovery(UUID id) { return store.file("recovery/" + id + ".yml"); }
    Session session(Player p) { return sessions.get(p.getUniqueId()); }
    Match match(Arena a) { return matches.computeIfAbsent(a.id, ignored -> new Match(a)); }
    boolean running(Session s) { return s != null && !s.restoring && (s.arena.solo() || match(s.arena).phase == Match.Phase.RUNNING); }
    boolean active(Session s) { return running(s) && !s.spectator && !s.eliminated; }
    boolean teleport(Player p, Location l) {
        if (l == null || l.getWorld() == null) return false;
        internalTeleport.add(p.getUniqueId());
        try { return p.teleport(l, TeleportCause.PLUGIN); }
        finally { internalTeleport.remove(p.getUniqueId()); }
    }
    void journal(Session s) throws IOException {
        var yaml = new YamlConfiguration();
        yaml.set("arena", s.arena.id); yaml.set("return", s.returnLocation); s.snapshot.save(yaml.createSection("snapshot"));
        DataFiles.save(yaml, recovery(s.player));
    }
    void join(Player p, Arena a, boolean spectate) {
        if (!p.hasPermission("lumigames.play")) { plugin.error(p, "You cannot join minigames."); return; }
        if (a.requirePermission && !p.hasPermission("lumigames.arena." + a.id)) { plugin.error(p, "You cannot enter this arena."); return; }
        if (spectate && !p.hasPermission("lumigames.spectate")) { plugin.error(p, "You cannot spectate."); return; }
        if (session(p) != null || recovery(p.getUniqueId()).exists()) { plugin.error(p, "Leave your current game or finish recovery first."); return; }
        if (p.isDead() || p.isInsideVehicle() || !p.getPassengers().isEmpty()) { plugin.error(p, "Respawn and leave your vehicle before joining."); return; }
        if (!a.enabled || !a.problems().isEmpty()) { plugin.error(p, "This arena is unavailable or needs setup."); return; }
        if (journals.containsKey(a.id) && journals.get(a.id).pending() && !matches.containsKey(a.id)) { plugin.error(p, "Arena rollback is pending. Ask an administrator to load its world and recover it."); return; }
        Match m = match(a);
        long count = m.players.stream().map(sessions::get).filter(Objects::nonNull).filter(s -> !s.spectator).count();
        if (!spectate && (count >= a.maxPlayers || !a.solo() && m.phase == Match.Phase.RUNNING && !a.lateJoin)) { plugin.error(p, "This match is full or already running."); return; }
        if (spectate && a.solo()) { plugin.error(p, "This arena does not support spectators."); return; }
        p.closeInventory();
        Session s = new Session(p.getUniqueId(), a, PlayerSnapshot.capture(p)); s.spectator = spectate;
        if (a.mode == Arena.Mode.SOLO) { s.checkpoint = store.checkpoint(s.player, a.id);
            if (s.checkpoint != null && a.checkpoints.containsKey(s.checkpoint)) {
                s.previousElapsed = store.elapsed(s.player, a.id);
                s.checkpointSpawn = a.checkpointSpawn(s.checkpoint, store.checkpointSpawn(s.player, a.id));
            }
            else s.checkpoint = null; }
        if (a.teams) s.team = chooseTeam(m);
        try { journal(s); } catch (IOException e) { plugin.failure(p, "Could not save your inventory; join cancelled", e); return; }
        sessions.put(s.player, s); m.players.add(s.player);
        Location spawn = spectate ? (a.spectator == null ? a.start : a.spectator)
            : a.solo() || m.phase == Match.Phase.RUNNING ? spawn(s) : a.lobby;
        if (!teleport(p, spawn)) { plugin.error(p, "Entry teleport failed. Restoring your state."); leave(p); return; }
        prepare(p, s, spectate);
        store.increment(s.player, a.id, "plays");
        plugin.message(p, "Joined " + a.name + ". /lg opens your game controls; /lg leave returns you.");
        if (a.solo() || m.phase == Match.Phase.RUNNING) startPlayer(p, s);
    }
    String chooseTeam(Match m) {
        long red = m.players.stream().map(sessions::get).filter(Objects::nonNull).filter(s -> !s.spectator && s.team.equals("red")).count();
        long blue = m.players.stream().map(sessions::get).filter(Objects::nonNull).filter(s -> !s.spectator && s.team.equals("blue")).count();
        return red <= blue ? "red" : "blue";
    }
    Location spawn(Session s) {
        Location cp = s.checkpoint == null ? null : s.arena.checkpointSpawn(s.checkpoint, s.checkpointSpawn);
        if (cp != null && s.arena.inside(cp)) return cp.clone();
        if (s.arena.teams) return (s.team.equals("red") ? s.arena.redSpawn : s.arena.blueSpawn).clone();
        return s.arena.start.clone();
    }
    void prepare(Player p, Session s, boolean spectator) {
        p.getInventory().clear(); p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setGameMode(spectator ? GameMode.SPECTATOR : GameMode.ADVENTURE);
        p.setAllowFlight(spectator || s.arena.flight); p.setFlying(spectator); p.setFireTicks(0); p.setFallDistance(0);
        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue()); p.setAbsorptionAmount(0);
        p.setNoDamageTicks(20); p.setFoodLevel(20); p.setSaturation(20); p.setExhaustion(0); p.setTotalExperience(0); p.setLevel(0); p.setExp(0);
        if (!spectator) {
            if (s.arena.breakBlocks || s.arena.placeBlocks) p.setGameMode(GameMode.SURVIVAL);
            String kit = s.arena.kits.containsKey(s.team) ? s.team : s.kit;
            p.getInventory().setContents(PlayerSnapshot.cloneItems(s.arena.kits.getOrDefault(kit, s.arena.loadout)));
            p.addPotionEffects(s.arena.kitEffects.getOrDefault(kit, List.of()));
            Match m = matches.get(s.arena.id);
            if (m != null && s.player.equals(m.juggernaut)) p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, effectDuration(m), 1));
        }
    }
    void startPlayer(Player p, Session s) {
        if (s.spectator) return;
        if (!teleport(p, spawn(s))) { plugin.error(p, "Start teleport failed."); leave(p); return; }
        prepare(p, s, false);
        p.showTitle(Title.title(Component.text(s.arena.name, NamedTextColor.AQUA), Component.text("Good luck!", NamedTextColor.GRAY)));
    }
    void leave(Player p) {
        Session s = session(p); if (s == null) { recover(p); return; }
        if (!s.restoring) {
            if (s.arena.mode == Arena.Mode.SOLO && s.checkpoint != null) {
                try { store.checkpoint(s.player, s.arena.id, s.checkpoint, s.checkpointSpawn, s.elapsed()); }
                catch (IOException e) { plugin.failure(p, "Could not update checkpoint time", e); }
            }
            s.restoring = true; s.carryingFlag = false;
            Match m = matches.get(s.arena.id);
            if (m != null) { m.players.remove(s.player); if (m.players.isEmpty()) cleanup(m); }
        }
        if (p.isDead()) return; // PlayerRespawnEvent retries on the next tick.
        if (restore(p, s.snapshot, s.returnLocation)) {
            sessions.remove(s.player);
            if (s.pendingWin) completeWin(p, s);
            plugin.message(p, "You left the minigame. Your inventory and location have been restored.");
        }
    }
    boolean restore(Player p, PlayerSnapshot snapshot, Location destination) {
        if (p.isDead()) return false;
        if (destination == null || destination.getWorld() == null) {
            plugin.error(p, "Your return world is unavailable. Your saved inventory is safe; recovery will retry."); return false;
        }
        if (!teleport(p, destination)) { plugin.error(p, "Return teleport was blocked. Recovery will retry."); return false; }
        try {
            snapshot.restore(p); p.saveData();
            Files.deleteIfExists(recovery(p.getUniqueId()).toPath()); return true;
        } catch (Exception e) { plugin.failure(p, "Recovery is pending; your saved state has been kept", e); return false; }
    }
    void recover(Player p) {
        if (session(p) != null) { if (session(p).restoring) leave(p); return; }
        File f = recovery(p.getUniqueId()); if (!f.exists() || p.isDead()) return;
        try {
            var yaml = DataFiles.read(f);
            PlayerSnapshot snapshot = PlayerSnapshot.load(Objects.requireNonNull(yaml.getConfigurationSection("snapshot")));
            Location destination = yaml.getLocation("return", snapshot.location());
            if (restore(p, snapshot, destination)) plugin.message(p, "Recovered your saved state from a previous minigame.");
        } catch (Exception e) { plugin.failure(p, "Could not read your recovery file. Ask an administrator; it has been preserved", e); }
    }
    boolean pending(Player p) { return session(p) != null || recovery(p.getUniqueId()).exists(); }
    void disconnect(Player p) {
        leave(p);
        sessions.remove(p.getUniqueId()); // The on-disk journal survives failed restores.
    }
    void cleanup(Match m) {
        matches.remove(m.arena.id);
        BlockJournal journal = journals.get(m.arena.id);
        if (journal != null) try { if (!journal.restore()) plugin.getLogger().warning("Rollback awaits a world for " + m.arena.id); }
        catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Rollback failed for " + m.arena.id, e); }
    }
    List<Session> players(Match m) {
        return m.players.stream().map(sessions::get).filter(Objects::nonNull).filter(s -> !s.spectator && !s.eliminated && !s.restoring).toList();
    }
    void tick() {
        for (Match m : List.copyOf(matches.values())) {
            if (m.arena.solo()) continue;
            int count = players(m).size();
            if (m.phase != Match.Phase.RUNNING) {
                if (count < m.arena.minPlayers) { m.phase = Match.Phase.WAITING; action(m, "Waiting · " + count + "/" + m.arena.minPlayers + " players"); continue; }
                if (m.phase == Match.Phase.WAITING) { m.phase = Match.Phase.COUNTDOWN; m.remaining = m.arena.countdown; }
                if (m.remaining-- <= 0) start(m); else action(m, "Starting in " + (m.remaining + 1) + "s");
            } else {
                if (m.arena.timed() && --m.remaining <= 0) { timeout(m); continue; }
                checkWinner(m);
                if (matches.containsKey(m.arena.id)) action(m, (m.arena.timed() ? "Time · " + m.remaining + "s" : "No time limit") + "  |  " + players(m).size() + " players" + (m.arena.teams ? "  |  Red " + m.redScore + " : " + m.blueScore + " Blue" : ""));
            }
        }
        for (Session s : List.copyOf(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player); if (p == null) continue;
            if (s.restoring) { leave(p); continue; }
            if (s.arena.solo() && active(s)) {
                if (s.arena.timed() && s.elapsed() / 1000 >= s.arena.duration) { plugin.message(p, "Time is up."); leave(p); }
                else p.sendActionBar(Component.text(s.arena.name + " · " + s.elapsed() / 1000 + "s" + (s.checkpoint == null ? "" : " · Checkpoint " + s.checkpoint), NamedTextColor.AQUA));
            }
        }
    }
    static int effectDuration(Match m) { return m.arena.timed() ? Math.max(1, m.remaining) * 20 : org.bukkit.potion.PotionEffect.INFINITE_DURATION; }
    void start(Match m) {
        m.phase = Match.Phase.RUNNING; m.remaining = m.arena.duration;
        List<Session> active = players(m);
        if (m.arena.mode == Arena.Mode.INFECTION) { active.forEach(s -> s.team = "blue"); if (!active.isEmpty()) active.getFirst().team = "red"; }
        if (m.arena.mode == Arena.Mode.JUGGERNAUT && !active.isEmpty()) m.juggernaut = active.getFirst().player;
        for (Session s : active) { s.started = System.currentTimeMillis(); Player p = Bukkit.getPlayer(s.player); if (p != null) startPlayer(p, s); }
        if (m.juggernaut != null) {
            Player boss = Bukkit.getPlayer(m.juggernaut);
            if (boss != null) { boss.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, effectDuration(m), 1)); plugin.message(boss, "You are the Juggernaut. Defeat other players to score."); }
        }
    }
    void action(Match m, String message) {
        for (UUID id : m.players) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendActionBar(Component.text(message, NamedTextColor.AQUA)); }
    }
    void checkpoint(Player p, String id) { checkpoint(p, id, null); }
    void checkpoint(Player p, String id, Location plateSpawn) {
        Session s = session(p); if (!active(s) || s.arena.mode != Arena.Mode.SOLO && s.arena.mode != Arena.Mode.RACE) return;
        List<String> order = new ArrayList<>(s.arena.checkpoints.keySet());
        int current = order.indexOf(s.checkpoint), reached = order.indexOf(id);
        if (reached < 0 || reached != current + 1 && !Objects.equals(id, s.checkpoint)) return;
        Location spawn = s.arena.checkpointSpawn(id, plateSpawn);
        if (spawn == null || !s.arena.inside(spawn)) return;
        // Moving across one plate must not write to disk every movement tick.
        if (Objects.equals(id, s.checkpoint) && Objects.equals(spawn, s.checkpointSpawn)) return;
        try { store.checkpoint(s.player, s.arena.id, id, spawn, s.elapsed()); }
        catch (IOException e) { plugin.failure(p, "Could not save checkpoint; try again", e); return; }
        s.checkpoint = id; s.checkpointSpawn = spawn;
        p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", 1, 1.3f);
        plugin.message(p, "Checkpoint saved · " + id);
    }
    void assignCheckpoint(UUID player, Arena arena, String id, Location spawn) throws IOException {
        if (arena.mode != Arena.Mode.SOLO && arena.mode != Arena.Mode.RACE) throw new IllegalArgumentException("Checkpoints are available only in solo courses and races.");
        if (!arena.checkpoints.containsKey(id)) throw new IllegalArgumentException("Unknown checkpoint: " + id);
        Location destination = arena.checkpointSpawn(id, spawn);
        if (destination == null || !arena.inside(destination)) throw new IllegalArgumentException("Checkpoint spawn must be inside a loaded arena world.");
        Session session = sessions.get(player);
        boolean playing = session != null && session.arena == arena;
        if (playing && !active(session)) throw new IllegalArgumentException("That player is spectating, waiting or recovering; their checkpoint cannot be changed now.");
        if (!playing && arena.mode == Arena.Mode.RACE) throw new IllegalArgumentException("The player must be actively racing in this arena.");
        long elapsed = playing ? session.elapsed() : store.elapsed(player, arena.id);
        store.checkpoint(player, arena.id, id, destination, elapsed);
        if (playing) { session.checkpoint = id; session.checkpointSpawn = destination; }
    }
    void revert(Player p) {
        Session s = session(p); if (!active(s)) { plugin.error(p, "You are not playing an active game."); return; }
        if (teleport(p, spawn(s))) { p.setFallDistance(0); p.setFireTicks(0); }
    }
    void finish(Player p) {
        Session s = session(p); if (!active(s)) return;
        if (s.arena.mode != Arena.Mode.SOLO && s.arena.mode != Arena.Mode.RACE) { plugin.error(p, "This game is won through its objective."); return; }
        String last = s.arena.checkpoints.isEmpty() ? null : new ArrayList<>(s.arena.checkpoints.keySet()).getLast();
        if (!Objects.equals(last, s.checkpoint)) { plugin.error(p, "Visit all checkpoints in order first."); return; }
        if (s.arena.solo()) win(s); else end(match(s.arena), Set.of(s.player));
    }
    void win(Session s) {
        Player p = Bukkit.getPlayer(s.player); if (p == null) return;
        s.pendingWin = true; s.finishTime = s.elapsed();
        leave(p);
    }
    void completeWin(Player p, Session s) {
        try { store.win(s.player, s.arena.id, s.finishTime); }
        catch (IOException e) { plugin.failure(p, "Could not save your result; rewards were withheld", e); return; }
        plugin.message(p, "You won " + s.arena.name + " in " + s.finishTime / 1000.0 + "s!");
        for (String command : s.arena.rewards) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", p.getName()).replace("{uuid}", s.player.toString()).replace("{arena}", s.arena.id));
    }
    void end(Match m, Set<UUID> winners) {
        // Mark finished before leave callbacks; all participants are restored, including spectators.
        m.phase = Match.Phase.WAITING;
        List<Session> all = m.players.stream().map(sessions::get).filter(Objects::nonNull).toList();
        for (Session s : all) {
            Player p = Bukkit.getPlayer(s.player); if (p == null) continue;
            if (winners.contains(s.player)) win(s); else { plugin.message(p, "Match ended."); leave(p); }
        }
        cleanup(m);
    }
    void timeout(Match m) {
        List<Session> active = players(m);
        if (m.arena.mode == Arena.Mode.INFECTION) { end(m, teamWinners(m, "blue")); return; }
        if (m.arena.mode == Arena.Mode.RACE) { end(m, Set.of()); return; }
        if (m.arena.teams) { end(m, m.redScore == m.blueScore ? Set.of() : teamWinners(m, m.redScore > m.blueScore ? "red" : "blue")); return; }
        int best = active.stream().mapToInt(s -> s.score).max().orElse(0);
        List<Session> leaders = active.stream().filter(s -> s.score == best).toList();
        end(m, best > 0 && leaders.size() == 1 ? Set.of(leaders.getFirst().player) : Set.of());
    }
    Set<UUID> teamWinners(Match m, String team) {
        Set<UUID> result = new HashSet<>(); for (Session s : players(m)) if (s.team.equals(team)) result.add(s.player); return result;
    }
    void checkWinner(Match m) {
        if (m.phase != Match.Phase.RUNNING) return;
        List<Session> active = players(m);
        if (active.isEmpty()) { end(m, Set.of()); return; }
        if (m.arena.mode == Arena.Mode.INFECTION) {
            if (active.stream().noneMatch(s -> s.team.equals("blue"))) end(m, teamWinners(m, "red"));
            return;
        }
        if (m.arena.mode == Arena.Mode.LIVES || m.arena.mode == Arena.Mode.SPLEEF) {
            Set<String> teams = new HashSet<>(); active.forEach(s -> teams.add(s.team));
            if (m.arena.teams && teams.size() == 1) end(m, teamWinners(m, active.getFirst().team));
            else if (!m.arena.teams && active.size() == 1) end(m, Set.of(active.getFirst().player));
        } else if (active.size() < 2 && m.arena.minPlayers >= 2) end(m, Set.of());
    }
    void score(Session s, int amount) {
        if (!active(s)) return;
        s.score += amount; Match m = match(s.arena);
        if (s.arena.teams) { if (s.team.equals("red")) m.redScore += amount; else m.blueScore += amount; }
        if (s.arena.solo() && s.score >= s.arena.target) { win(s); return; }
        if (s.arena.teams && (m.redScore >= s.arena.target || m.blueScore >= s.arena.target)) end(m, teamWinners(m, s.team));
        else if (!s.arena.teams && s.score >= s.arena.target) end(m, Set.of(s.player));
    }
    void died(Player p, Player killer) {
        Session victim = session(p); if (!active(victim)) return;
        victim.carryingFlag = false; store.increment(victim.player, victim.arena.id, "deaths");
        Session attacker = killer == null ? null : session(killer);
        if (active(attacker) && attacker != victim && attacker.arena == victim.arena && (!victim.arena.teams || !attacker.team.equals(victim.team))) {
            store.increment(attacker.player, attacker.arena.id, "kills");
            switch (victim.arena.mode) {
                case KILLS -> score(attacker, 1);
                case JUGGERNAUT -> {
                    Match m = match(victim.arena);
                    if (attacker.player.equals(m.juggernaut)) score(attacker, 1);
                    else if (victim.player.equals(m.juggernaut)) {
                        m.juggernaut = attacker.player;
                        killer.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, effectDuration(m), 1));
                        plugin.message(killer, "You are now the Juggernaut!");
                    }
                }
                default -> {}
            }
        }
        if (victim.restoring) return;
        if (victim.arena.mode == Arena.Mode.INFECTION) victim.team = "red";
        if (victim.arena.mode == Arena.Mode.LIVES || victim.arena.mode == Arena.Mode.SPLEEF) {
            if (--victim.lives <= 0) { victim.eliminated = true; victim.spectator = true; }
        }
        checkWinner(match(victim.arena));
    }
    void recoverBlocks() throws IOException {
        File dir = store.file("rollback"); File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null) return;
        for (File f : files) {
            String id = f.getName().replaceFirst("\\.log$", "");
            BlockJournal j = new BlockJournal(f); j.load(); journals.put(id, j);
            if (!j.restore()) plugin.getLogger().warning("Load the missing world and run /lg admin recover: " + id);
        }
    }
    boolean record(org.bukkit.block.Block block, Arena a) {
        try { return journals.computeIfAbsent(a.id, id -> new BlockJournal(store.file("rollback/" + id + ".log"))).record(block, plugin.getConfig().getInt("max-recorded-blocks", 50000)); }
        catch (IOException e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cannot record arena block; change cancelled", e); return false; }
    }
}
