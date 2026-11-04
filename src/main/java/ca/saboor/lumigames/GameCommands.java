package ca.saboor.lumigames;

import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

final class GameCommands implements CommandExecutor, TabCompleter {
    final Lumigames plugin;
    final Store store;
    final GameEngine engine;
    static final List<String> POSITIONS = List.of("start", "lobby", "exit", "spectator", "corner1", "corner2", "red-spawn", "blue-spawn", "red-flag", "blue-flag", "treasure");
    GameCommands(Lumigames plugin) { this.plugin = plugin; store = plugin.store; engine = plugin.engine; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("migrate")) {
            try { migrate(sender, Arrays.copyOfRange(args, 2, args.length)); }
            catch (IllegalArgumentException | IOException e) { sender.sendMessage("Migration failed: " + e.getMessage()); }
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("set-checkpoint")) {
            try { setPlayerCheckpoint(sender, Arrays.copyOfRange(args, 2, args.length)); }
            catch (IllegalArgumentException | IOException e) { sender.sendMessage("Could not set checkpoint: " + e.getMessage()); }
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Lumigames setup and play commands require a player. Data files can be edited while the server is stopped."); return true; }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("list")) { plugin.menus.home(p, 0); return true; }
            String action = args[0].toLowerCase(Locale.ROOT);
            if (!p.hasPermission("lumigames.play") && !action.equals("leave") && !action.equals("quit") && !action.equals("admin")) throw new IllegalArgumentException("You cannot play minigames.");
            switch (action) {
                case "leave", "quit" -> engine.leave(p);
                case "restart" -> restart(p);
                case "revert", "checkpoint" -> engine.revert(p);
                case "join", "spectate" -> engine.join(p, arena(required(args, 1)), action.equals("spectate"));
                case "info" -> plugin.menus.detail(p, arena(required(args, 1)));
                case "stats" -> plugin.menus.stats(p, args.length > 1 ? arena(args[1]) : current(p));
                case "loadout" -> loadout(p, required(args, 1));
                case "hint" -> hint(p);
                case "team" -> team(p, required(args, 1));
                case "admin" -> admin(p, Arrays.copyOfRange(args, 1, args.length));
                case "help" -> help(p);
                default -> help(p);
            }
        } catch (IllegalArgumentException e) { plugin.error(p, e.getMessage()); }
        catch (IOException e) { plugin.failure(p, "Could not save data", e); }
        return true;
    }
    void migrate(CommandSender sender, String[] args) throws IOException {
        if (!sender.hasPermission("lumigames.admin")) throw new IllegalArgumentException("You cannot migrate arenas.");
        String action = args.length == 0 ? "preview" : args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("preview") && !action.equals("apply")) throw new IllegalArgumentException("Usage: /lg admin migrate <preview|apply> [Minigames data folder or arena YAML]");
        LegacyMigration migration = new LegacyMigration(store);
        java.nio.file.Path source = args.length <= 1 ? migration.defaultSource() : java.nio.file.Path.of(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        LegacyMigration.Plan plan = migration.preview(source);
        for (String line : plan.lines()) {
            plugin.getLogger().info(line);
            if (!(sender instanceof ConsoleCommandSender)) sender.sendMessage(line);
        }
        if (action.equals("preview")) sender.sendMessage("Preview only: no files changed. Run /lg admin migrate apply with the same source to import.");
        else {
            LegacyMigration.Result result = migration.apply(plan);
            sender.sendMessage("Imported " + result.imported() + " disabled arenas. Report: " + result.report() + ". Backup: " + result.backup());
        }
    }
    void setPlayerCheckpoint(CommandSender sender, String[] args) throws IOException {
        if (!sender.hasPermission("lumigames.admin")) throw new IllegalArgumentException("You cannot change players' checkpoints.");
        if (args.length < 3 || args.length > 5) throw new IllegalArgumentException("Usage: /lg admin set-checkpoint <arena> <player|UUID> <checkpoint> [plate-number] [teleport]");
        Arena arena = arena(args[0]); String id = args[2];
        if (!arena.checkpoints.containsKey(id)) throw new IllegalArgumentException("Unknown checkpoint: " + id);
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID uuid;
        if (online != null) uuid = online.getUniqueId();
        else {
            try { uuid = UUID.fromString(args[1]); }
            catch (IllegalArgumentException e) {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (cached == null) throw new IllegalArgumentException("Unknown player. Use an online player name, a cached name or an explicit UUID.");
                uuid = cached.getUniqueId();
            }
            online = Bukkit.getPlayer(uuid);
        }
        List<Location> plates = arena.checkpointPlates.getOrDefault(id, List.of());
        Location spawn = arena.checkpoints.get(id); boolean teleport = false, selectedPlate = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("teleport") && !teleport) teleport = true;
            else {
                if (selectedPlate || plates.isEmpty()) throw new IllegalArgumentException("Specify one linked plate number, or omit it to use the checkpoint's default spawn.");
                spawn = plates.get(integer(args[i], 1, plates.size()) - 1); selectedPlate = true;
            }
        }
        Session session = online == null ? null : engine.session(online);
        if (teleport && (session == null || session.arena != arena || !engine.active(session))) throw new IllegalArgumentException("Teleport requires the player to be actively playing this arena.");
        engine.assignCheckpoint(uuid, arena, id, spawn);
        sender.sendMessage("Set " + args[1] + "'s checkpoint in " + arena.id + " to " + id + (selectedPlate ? " at linked plate " + (plates.indexOf(spawn) + 1) : " at its default spawn") + ".");
        if (online != null) plugin.message(online, "An administrator set your checkpoint in " + arena.name + " to " + id + "." + (session != null && session.arena == arena && !teleport ? " Use /lg revert to return there." : ""));
        if (teleport) {
            if (engine.teleport(online, engine.spawn(session))) { online.setFallDistance(0); online.setFireTicks(0); }
            else sender.sendMessage("Checkpoint saved, but the teleport was blocked. The player can retry with /lg revert.");
        }
    }
    Arena current(Player p) { Session s = engine.session(p); if (s == null) throw new IllegalArgumentException("Specify an arena ID."); return s.arena; }
    Arena arena(String id) { Arena a = store.arenas.get(id.toLowerCase(Locale.ROOT)); if (a == null) throw new IllegalArgumentException("Unknown arena: " + id); return a; }
    static String required(String[] args, int i) { if (args.length <= i) throw new IllegalArgumentException("Missing argument. Use /lg help or /lg admin help."); return args[i]; }
    void adminPermission(Player p) { if (!p.hasPermission("lumigames.admin")) throw new IllegalArgumentException("You cannot manage arenas."); }
    void editable(Arena a) { if (engine.matches.containsKey(a.id)) throw new IllegalArgumentException("Stop the active match before editing this arena."); }
    void team(Player p, String name) {
        Session s = engine.session(p); if (s == null || s.restoring || s.spectator || !s.arena.teams || engine.running(s)) throw new IllegalArgumentException("Choose a team in a team game's lobby.");
        if (!name.equals("red") && !name.equals("blue")) throw new IllegalArgumentException("Choose red or blue.");
        if (s.team.equals(name)) return;
        Match m = engine.match(s.arena);
        long selected = engine.players(m).stream().filter(other -> other.team.equals(name)).count();
        long other = engine.players(m).stream().filter(session -> !session.team.equals(name)).count();
        if (selected >= other) throw new IllegalArgumentException("That would unbalance the teams.");
        s.team = name; plugin.message(p, "Joined " + name + " team.");
    }
    void restart(Player p) throws IOException {
        Session s = engine.session(p);
        if (!engine.active(s) || s.arena.mode != Arena.Mode.SOLO) throw new IllegalArgumentException("Restart is available while playing a solo course.");
        store.checkpoint(s.player, s.arena.id, null, 0);
        s.checkpoint = null; s.checkpointSpawn = null; s.previousElapsed = 0; s.started = System.currentTimeMillis();
        engine.startPlayer(p, s); plugin.message(p, "Course restarted; saved progress cleared.");
    }
    void loadout(Player p, String kit) {
        Session s = engine.session(p);
        if (s == null || s.restoring || s.spectator || engine.running(s)) throw new IllegalArgumentException("Choose a loadout while waiting in the lobby.");
        if (!kit.equals("default") && !s.arena.kits.containsKey(kit)) throw new IllegalArgumentException("Unknown loadout.");
        s.kit = kit; engine.prepare(p, s, false); plugin.message(p, "Selected loadout: " + kit);
    }
    void hint(Player p) {
        Session s = engine.session(p);
        if (!engine.active(s) || s.arena.mode != Arena.Mode.TREASURE) throw new IllegalArgumentException("Hints are available during treasure hunts.");
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < s.arena.treasures.size(); i++) if (!s.treasures.contains(i)) nearest = Math.min(nearest, p.getLocation().distance(s.arena.treasures.get(i)));
        plugin.message(p, nearest == Double.MAX_VALUE ? "All treasures found." : "The nearest undiscovered treasure is about " + Math.round(nearest) + " blocks away.");
    }
    void admin(Player p, String[] args) throws IOException {
        adminPermission(p);
        if (args.length == 0) { plugin.menus.admin(p, 0); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) { adminHelp(p); return; }
        if (action.equals("set-checkpoint")) { setPlayerCheckpoint(p, Arrays.copyOfRange(args, 1, args.length)); return; }
        if (action.equals("migrate")) { migrate(p, Arrays.copyOfRange(args, 1, args.length)); return; }
        if (action.equals("create")) {
            String id = required(args, 1).toLowerCase(Locale.ROOT);
            if (store.arenas.containsKey(id)) throw new IllegalArgumentException("That arena ID already exists.");
            Arena.Mode mode;
            try { mode = Arena.Mode.valueOf(required(args, 2).toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Modes: " + Arrays.toString(Arena.Mode.values())); }
            Arena a = new Arena(id, mode); store.arenas.put(id, a);
            try { store.saveArenas(); } catch (IOException e) { store.arenas.remove(id); throw e; }
            plugin.menus.edit(p, a); return;
        }
        if (action.equals("recover")) {
            if (!engine.matches.isEmpty()) throw new IllegalArgumentException("Stop all matches before recovering blocks.");
            engine.recoverBlocks(); Bukkit.getOnlinePlayers().forEach(engine::recover); plugin.message(p, "Recovery attempted; check console for worlds still unavailable."); return;
        }
        Arena a = arena(required(args, 1));
        if (action.equals("edit")) { plugin.menus.edit(p, a); return; }
        if (action.equals("stop")) { Match m = engine.matches.get(a.id); if (m != null) engine.end(m, Set.of()); plugin.message(p, "Stopped " + a.name); return; }
        if (action.equals("start")) {
            Match m = engine.matches.get(a.id);
            if (m == null || a.solo() || m.phase == Match.Phase.RUNNING || engine.players(m).size() < a.minPlayers) throw new IllegalArgumentException("A waiting multiplayer match with enough players is required.");
            engine.start(m); return;
        }
        if (action.equals("score")) {
            if (a.mode != Arena.Mode.CUSTOM) throw new IllegalArgumentException("Manual scoring is available for CUSTOM games.");
            Player target = Bukkit.getPlayerExact(required(args, 2));
            Session s = target == null ? null : engine.session(target);
            if (!engine.active(s) || s.arena != a) throw new IllegalArgumentException("That player is not active in this arena.");
            engine.score(s, integer(required(args, 3), 1, 10000)); return;
        }
        editable(a);
        // Transactional edits: restore the in-memory definition if validation or persistence fails.
        var backup = new org.bukkit.configuration.file.YamlConfiguration(); a.save(backup);
        try {
            switch (action) {
                case "enable" -> {
                    List<String> errors = a.problems();
                    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ", errors));
                    if (overlaps(a)) throw new IllegalArgumentException("Arena bounds overlap another enabled arena.");
                    a.enabled = true;
                }
                case "disable" -> a.enabled = false;
                case "set" -> position(p, a, required(args, 2));
                case "checkpoint" -> {
                    String id = required(args, 2); if (!Arena.validId(id)) throw new IllegalArgumentException("Invalid checkpoint ID.");
                    if (!a.inside(p.getLocation())) throw new IllegalArgumentException("Stand inside the arena.");
                    a.checkpoints.put(id, p.getLocation().clone());
                }
                case "link-checkpoint" -> linkCheckpoint(p, a, required(args, 2), false);
                case "unlink-checkpoint" -> linkCheckpoint(p, a, required(args, 2), true);
                case "remove-checkpoint" -> { String id = required(args, 2); a.checkpoints.remove(id); a.checkpointPlates.remove(id); }
                case "clear-treasures" -> a.treasures.clear();
                case "loadout" -> {
                    if (engine.pending(p)) throw new IllegalArgumentException("Leave your minigame before saving a loadout.");
                    if (args.length <= 2) a.loadout = PlayerSnapshot.cloneItems(p.getInventory().getContents());
                    else {
                        String kit = args[2]; if (!Arena.validId(kit)) throw new IllegalArgumentException("Invalid loadout ID.");
                        a.kits.put(kit, PlayerSnapshot.cloneItems(p.getInventory().getContents())); a.kitEffects.put(kit, List.copyOf(p.getActivePotionEffects()));
                    }
                }
                case "remove-loadout" -> { String kit = required(args, 2); a.kits.remove(kit); a.kitEffects.remove(kit); }
                case "option" -> option(a, required(args, 2), String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                case "reward" -> {
                    String reward = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    if (reward.equals("clear")) a.rewards.clear();
                    else if (reward.isBlank() || reward.startsWith("/")) throw new IllegalArgumentException("Use a console command without /; {player}, {uuid} and {arena} are supported.");
                    else a.rewards.add(reward);
                }
                case "delete" -> {
                    if (!required(args, 2).equals("confirm")) throw new IllegalArgumentException("Use the delete confirmation dialog or append confirm.");
                    if (engine.journals.containsKey(a.id) && engine.journals.get(a.id).pending()) throw new IllegalArgumentException("Recover this arena's blocks before deleting it.");
                    store.arenas.remove(a.id);
                }
                default -> throw new IllegalArgumentException("Unknown admin action. /lg admin help");
            }
            if (a.enabled && (!a.problems().isEmpty() || overlaps(a))) throw new IllegalArgumentException("Disable this arena before making changes that invalidate its setup.");
            store.saveArenas();
        } catch (IOException | RuntimeException e) { store.arenas.put(a.id, Arena.load(a.id, backup)); throw e; }
        plugin.message(p, "Saved " + a.name + ".");
        if (action.equals("link-checkpoint") || action.equals("unlink-checkpoint")) plugin.message(p, "Checkpoint " + args[2] + " now has " + a.checkpointPlates.getOrDefault(args[2], List.of()).size() + " linked plate(s).");
    }
    void linkCheckpoint(Player p, Arena a, String id, boolean unlink) {
        if (!Arena.validId(id)) throw new IllegalArgumentException("Invalid checkpoint ID.");
        if (a.mode != Arena.Mode.SOLO && a.mode != Arena.Mode.RACE) throw new IllegalArgumentException("Checkpoint plates are available in solo courses and races.");
        var plate = p.getTargetBlockExact(6);
        if (plate == null || !Arena.pressurePlate(plate.getType())) plate = p.getLocation().getBlock();
        if (!Arena.pressurePlate(plate.getType())) throw new IllegalArgumentException("Look at a pressure plate within six blocks, or stand on it.");
        Location location = plate.getLocation();
        if (!a.inside(location)) throw new IllegalArgumentException("The pressure plate must be inside the arena.");
        if (unlink && !a.checkpoints.containsKey(id)) throw new IllegalArgumentException("Unknown checkpoint: " + id);
        Arena.CheckpointPlate existing = a.checkpointAt(location);
        if (!unlink && existing != null && !existing.id().equals(id)) throw new IllegalArgumentException("That plate is already linked to checkpoint " + existing.id() + ". Unlink it first.");
        List<Location> plates = a.checkpointPlates.get(id);
        if (plates == null) {
            plates = new ArrayList<>();
            Location original = a.checkpoints.get(id);
            if (original != null && Arena.pressurePlate(original.getBlock().getType())) plates.add(original.clone());
            a.checkpointPlates.put(id, plates);
        }
        if (unlink) {
            if (!plates.removeIf(spawn -> Arena.sameBlock(spawn, location))) throw new IllegalArgumentException("That plate is not linked to checkpoint " + id + ".");
            if (Arena.sameBlock(a.checkpoints.get(id), location) && !plates.isEmpty()) a.checkpoints.put(id, plates.getFirst().clone());
        } else {
            Location spawn = location.clone().add(0.5, 0.1, 0.5);
            spawn.setYaw(p.getLocation().getYaw()); spawn.setPitch(p.getLocation().getPitch());
            plates.removeIf(previous -> Arena.sameBlock(previous, location)); plates.add(spawn);
            a.checkpoints.putIfAbsent(id, spawn.clone());
        }
    }
    boolean overlaps(Arena a) {
        if (a.corner1 == null || a.corner2 == null) return false;
        for (Arena b : store.arenas.values()) {
            if (a == b || !b.enabled || b.corner1 == null || b.corner2 == null || !Objects.equals(a.corner1.getWorld(), b.corner1.getWorld())) continue;
            if (Math.min(a.corner1.getBlockX(), a.corner2.getBlockX()) <= Math.max(b.corner1.getBlockX(), b.corner2.getBlockX()) && Math.max(a.corner1.getBlockX(), a.corner2.getBlockX()) >= Math.min(b.corner1.getBlockX(), b.corner2.getBlockX())
                && Math.min(a.corner1.getBlockY(), a.corner2.getBlockY()) <= Math.max(b.corner1.getBlockY(), b.corner2.getBlockY()) && Math.max(a.corner1.getBlockY(), a.corner2.getBlockY()) >= Math.min(b.corner1.getBlockY(), b.corner2.getBlockY())
                && Math.min(a.corner1.getBlockZ(), a.corner2.getBlockZ()) <= Math.max(b.corner1.getBlockZ(), b.corner2.getBlockZ()) && Math.max(a.corner1.getBlockZ(), a.corner2.getBlockZ()) >= Math.min(b.corner1.getBlockZ(), b.corner2.getBlockZ())) return true;
        }
        return false;
    }
    void position(Player p, Arena a, String kind) {
        Location l = p.getLocation().clone();
        switch (kind) {
            case "start" -> a.start = l; case "lobby" -> a.lobby = l; case "exit" -> a.exit = l;
            case "spectator" -> a.spectator = l; case "corner1" -> a.corner1 = l; case "corner2" -> a.corner2 = l;
            case "red-spawn" -> a.redSpawn = l; case "blue-spawn" -> a.blueSpawn = l;
            case "red-flag" -> a.redFlag = l; case "blue-flag" -> a.blueFlag = l;
            case "treasure" -> {
                BlockTarget target = target(p); a.treasures.add(target.location());
            }
            default -> throw new IllegalArgumentException("Positions: " + POSITIONS);
        }
    }
    record BlockTarget(Location location) {}
    BlockTarget target(Player p) { var block = p.getTargetBlockExact(6); if (block == null) throw new IllegalArgumentException("Look at a block within six blocks."); return new BlockTarget(block.getLocation()); }
    static int integer(String value, int min, int max) {
        try { int n = Integer.parseInt(value); if (n >= min && n <= max) return n; } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Enter an integer between " + min + " and " + max + ".");
    }
    static boolean bool(String value) { if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Use true or false."); return Boolean.parseBoolean(value); }
    void option(Arena a, String key, String value) {
        switch (key) {
            case "name" -> { if (value.isBlank() || value.length() > 64) throw new IllegalArgumentException("Name must contain 1–64 characters."); a.name = value; }
            case "description" -> { if (value.length() > 500) throw new IllegalArgumentException("Description is limited to 500 characters."); a.description = value; }
            case "min-players" -> a.minPlayers = integer(value, 1, a.maxPlayers);
            case "max-players" -> a.maxPlayers = integer(value, a.minPlayers, 100);
            case "duration" -> a.duration = integer(value, 0, 86400);
            case "countdown" -> a.countdown = integer(value, 0, 300);
            case "lives" -> a.lives = integer(value, 1, 100);
            case "target" -> a.target = integer(value, 1, 10000);
            case "teams" -> { if (a.mode == Arena.Mode.CTF || a.mode == Arena.Mode.INFECTION) throw new IllegalArgumentException("Teams are required for this mode."); a.teams = bool(value); }
            case "pvp" -> a.pvp = bool(value);
            case "flight" -> a.flight = bool(value);
            case "paintball" -> a.paintball = bool(value);
            case "infinite-ammo" -> a.infiniteAmmo = bool(value);
            case "require-permission" -> a.requirePermission = bool(value);
            case "break-blocks" -> a.breakBlocks = bool(value);
            case "place-blocks" -> a.placeBlocks = bool(value);
            case "late-join" -> { if (a.mode == Arena.Mode.INFECTION || a.mode == Arena.Mode.LIVES || a.mode == Arena.Mode.SPLEEF || a.mode == Arena.Mode.RACE) throw new IllegalArgumentException("Late joins would bypass this mode's objective."); a.lateJoin = bool(value); }
            default -> throw new IllegalArgumentException("Unknown option.");
        }
    }
    void help(Player p) { plugin.message(p, "/lg · /lg join <arena> · /lg spectate <arena> · /lg leave · /lg revert · /lg team <red|blue> · /lg stats <arena> · /lg admin"); }
    void adminHelp(Player p) {
        plugin.message(p, "/lg admin create <id> <mode> · edit <id> · set <id> <position> · checkpoint <id> <name> · link-checkpoint/unlink-checkpoint <id> <name> · set-checkpoint <id> <player> <checkpoint> [plate-number] [teleport] · remove-checkpoint <id> <name> · loadout <id> · option <id> <key> <value> · reward <id> <console command|clear> · enable/disable/start/stop <id> · score <id> <player> <points> · delete <id> confirm · recover · migrate <preview|apply> [source]");
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = new ArrayList<>();
        if (args.length == 1) { values.addAll(List.of("join", "leave", "revert", "restart", "list", "info", "stats", "spectate", "team", "loadout", "hint", "help")); if (sender.hasPermission("lumigames.admin")) values.add("admin"); }
        else if (args[0].equals("admin") && sender.hasPermission("lumigames.admin")) {
            if (args.length == 2) values.addAll(List.of("create", "edit", "set", "checkpoint", "link-checkpoint", "unlink-checkpoint", "set-checkpoint", "remove-checkpoint", "clear-treasures", "loadout", "remove-loadout", "option", "reward", "enable", "disable", "start", "stop", "score", "delete", "recover", "migrate", "help"));
            else if (args.length == 3 && args[1].equals("migrate")) values.addAll(List.of("preview", "apply"));
            else if (args.length == 3) values.addAll(store.arenas.keySet());
            else if (args[1].equals("set-checkpoint") && args.length == 4) Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(values::add);
            else if (args[1].equals("set-checkpoint") && args.length == 5 && store.arenas.containsKey(args[2])) values.addAll(store.arenas.get(args[2]).checkpoints.keySet());
            else if (args[1].equals("set-checkpoint") && args.length >= 6 && store.arenas.containsKey(args[2])) {
                values.add("teleport");
                if (args.length == 6) { int count = store.arenas.get(args[2]).checkpointPlates.getOrDefault(args[4], List.of()).size(); for (int i = 1; i <= count; i++) values.add(Integer.toString(i)); }
            }
            else if (args.length == 4 && args[1].equals("create")) for (Arena.Mode mode : Arena.Mode.values()) values.add(mode.name().toLowerCase(Locale.ROOT));
            else if (args.length == 4 && Set.of("checkpoint", "link-checkpoint", "unlink-checkpoint", "remove-checkpoint").contains(args[1]) && store.arenas.containsKey(args[2])) values.addAll(store.arenas.get(args[2]).checkpoints.keySet());
            else if (args.length == 4 && args[1].equals("set")) values.addAll(POSITIONS);
            else if (args.length == 4 && args[1].equals("option")) values.addAll(List.of("name", "description", "min-players", "max-players", "duration", "countdown", "lives", "target", "teams", "pvp", "break-blocks", "place-blocks", "late-join", "flight", "paintball", "infinite-ammo", "require-permission"));
        } else if (args.length == 2 && args[0].equals("team")) values.addAll(List.of("red", "blue"));
        else if (args.length == 2 && args[0].equals("loadout") && sender instanceof Player p && engine.session(p) != null) { values.add("default"); values.addAll(engine.session(p).arena.kits.keySet()); }
        else if (args.length == 2) store.arenas.values().stream().filter(a -> a.enabled || sender.hasPermission("lumigames.admin")).map(a -> a.id).forEach(values::add);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
