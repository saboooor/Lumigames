package ca.saboor.lumigames;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Native, paginated Lumi-style menus. Each opening invalidates older callbacks. */
final class Menus {
    final Lumigames plugin;
    final Map<UUID, UUID> views = new HashMap<>();
    record Button(String icon, String label, String description, Runnable action) {}
    Menus(Lumigames plugin) { this.plugin = plugin; }
    Button button(String icon, String label, String description, Runnable action) { return new Button(icon, label, description, action); }
    Component label(String icon, String text) {
        String[] v = Bukkit.getMinecraftVersion().split("\\.");
        int major = Integer.parseInt(v[0]), minor = Integer.parseInt(v[1]), patch = v.length > 2 ? Integer.parseInt(v[2].replaceAll("[^0-9].*", "")) : 0;
        Key atlas = Key.key(major > 1 || minor > 21 || minor == 21 && patch >= 11 ? "minecraft:items" : "minecraft:blocks");
        return Component.object(ObjectContents.sprite(atlas, Key.key("minecraft:item/" + icon))).append(Component.text(" " + text));
    }
    void show(Player p, String title, String message, List<Button> buttons) {
        if (!p.isOnline()) return;
        UUID token = UUID.randomUUID(); views.put(p.getUniqueId(), token);
        List<ActionButton> actions = buttons.stream().map(b -> ActionButton.create(label(b.icon, b.label), Component.text(b.description), 200,
            DialogAction.customClick((response, audience) -> {
                if (audience instanceof Player actor && actor.getUniqueId().equals(p.getUniqueId())) plugin.sync(() -> {
                    if (!actor.isOnline() || !token.equals(views.get(actor.getUniqueId()))) return;
                    views.remove(actor.getUniqueId());
                    try { b.action.run(); } catch (IllegalArgumentException e) { plugin.error(actor, e.getMessage()); }
                });
            }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))).toList();
        p.showDialog(Dialog.create(f -> f.empty().base(DialogBase.builder(Component.text(title)).pause(false)
            .body(List.of(DialogBody.plainMessage(Component.text(message), 420))).build())
            .type(DialogType.multiAction(actions, ActionButton.create(label("barrier", "Close"), null, 200, null), 2))));
    }
    Arena fresh(String id) { return plugin.commands.arena(id); }
    void command(Player p, String args) { p.performCommand("lumigames " + args); }
    void home(Player p, int page) {
        if (!p.hasPermission("lumigames.play")) { plugin.error(p, "You cannot play minigames."); return; }
        Session s = plugin.engine.session(p);
        if (s != null) { playing(p, s); return; }
        if (plugin.engine.pending(p)) { plugin.engine.recover(p); return; }
        List<Arena> all = plugin.store.arenas.values().stream().filter(a -> a.enabled).sorted(Comparator.comparing(a -> a.name)).toList();
        List<Button> buttons = new ArrayList<>();
        int selected = Math.max(0, Math.min(page, Math.max(0, (all.size() - 1) / 10)));
        for (Arena a : all.stream().skip(selected * 10L).limit(10).toList()) {
            Match m = plugin.engine.matches.get(a.id); int count = m == null ? 0 : plugin.engine.players(m).size();
            buttons.add(button("slime_ball", a.name + " · " + count + "/" + a.maxPlayers, a.mode + " · " + a.description, () -> detail(p, fresh(a.id))));
        }
        if (selected > 0) buttons.add(button("arrow", "Previous", "Previous page", () -> home(p, selected - 1)));
        if ((selected + 1) * 10 < all.size()) buttons.add(button("arrow", "Next", "Next page", () -> home(p, selected + 1)));
        if (p.hasPermission("lumigames.admin")) buttons.add(button("command_block", "Manage arenas", "Create and configure games", () -> admin(p, 0)));
        show(p, "Lumigames", all.isEmpty() ? "No arenas are available yet." : "Choose your next game. Your inventory is saved before joining. Page " + (selected + 1) + ".", buttons);
    }
    void detail(Player p, Arena a) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(button("ender_pearl", "Join game", "Resume saved progress in solo games", () -> plugin.engine.join(p, fresh(a.id), false)));
        if (!a.solo()) buttons.add(button("spyglass", "Spectate", "Watch without taking part", () -> plugin.engine.join(p, fresh(a.id), true)));
        buttons.add(button("book", "Your statistics", "Wins, kills, deaths and best completion time", () -> stats(p, fresh(a.id))));
        buttons.add(button("gold_ingot", "Leaderboard", "Top ten players by wins", () -> leaderboard(p, fresh(a.id))));
        buttons.add(button("arrow", "Back", "Return to the game browser", () -> home(p, 0)));
        show(p, a.name, a.description + "\n" + a.mode + " · " + a.minPlayers + "–" + a.maxPlayers + " players · " + a.timeLimitLabel() + "\n" + (a.mode == Arena.Mode.SOLO ? "Saved checkpoints resume when you return." : "Multiplayer matches start with fresh progress."), buttons);
    }
    void playing(Player p, Session s) {
        List<Button> buttons = new ArrayList<>();
        if (!s.spectator) buttons.add(button("ender_pearl", "Return to checkpoint", "Return to your last checkpoint or the start", () -> plugin.engine.revert(p)));
        if (s.arena.teams && !plugin.engine.running(s) && !s.spectator) {
            buttons.add(button("red_wool", "Red team", "Choose a balanced team", () -> plugin.commands.team(p, "red")));
            buttons.add(button("blue_wool", "Blue team", "Choose a balanced team", () -> plugin.commands.team(p, "blue")));
        }
        if (s.arena.mode == Arena.Mode.SOLO && !s.restoring) buttons.add(button("clock", "Restart course", "Clear saved checkpoint and restart the timer", () -> confirm(p, "Restart course?", "Clear this course's saved progress and start again?", () -> command(p, "restart"), () -> home(p, 0))));
        if (!plugin.engine.running(s) && !s.spectator) buttons.add(button("chest", "Choose loadout", "Select a kit before the match starts", () -> loadouts(p)));
        if (s.arena.mode == Arena.Mode.TREASURE) buttons.add(button("compass", "Treasure hint", "Distance to the nearest undiscovered treasure", () -> plugin.commands.hint(p)));
        buttons.add(button("book", "Statistics", "View your results", () -> stats(p, fresh(s.arena.id))));
        buttons.add(button("oak_door", "Leave game", "Restore your inventory and return to your previous location", () -> plugin.engine.leave(p)));
        show(p, s.arena.name, (s.spectator ? "Spectating" : "Score: " + s.score + " · Lives: " + s.lives) + (s.team.isEmpty() ? "" : " · Team: " + s.team) + "\nCheckpoint: " + Objects.toString(s.checkpoint, "Start") + (s.carryingFlag ? "\nYou are carrying the enemy flag!" : ""), buttons);
    }
    void loadouts(Player p) {
        Session s = plugin.engine.session(p); if (s == null) return;
        List<Button> buttons = new ArrayList<>();
        List<String> kits = new ArrayList<>(s.arena.kits.keySet()); if (!kits.contains("default")) kits.addFirst("default");
        for (String kit : kits) buttons.add(button("chest", kit, "Select this loadout", () -> { plugin.commands.loadout(p, kit); home(p, 0); }));
        show(p, "Loadouts", "Team kits named red and blue take priority over selected loadouts.", buttons);
    }
    void stats(Player p, Arena a) {
        Store store = plugin.store; UUID id = p.getUniqueId();
        show(p, a.name + " · Statistics", "Played: " + store.stat(id, a.id, "plays") + "\nWins: " + store.stat(id, a.id, "wins") + "\nKills: " + store.stat(id, a.id, "kills") + "\nDeaths: " + store.stat(id, a.id, "deaths") + "\nBest completion: " + (store.stat(id, a.id, "best-ms") == 0 ? "No completed games" : store.stat(id, a.id, "best-ms") / 1000.0 + "s"),
            List.of(button("arrow", "Back", "Return", () -> home(p, 0))));
    }
    void leaderboard(Player p, Arena a) {
        List<String> rows = plugin.store.leaderboard(a.id);
        show(p, a.name + " · Leaderboard", rows.isEmpty() ? "No completed games yet." : String.join("\n", rows), List.of(button("arrow", "Back", "Return", () -> detail(p, fresh(a.id)))));
    }
    void admin(Player p, int page) {
        plugin.commands.adminPermission(p);
        List<Arena> all = new ArrayList<>(plugin.store.arenas.values());
        int selected = Math.max(0, Math.min(page, Math.max(0, (all.size() - 1) / 10)));
        List<Button> buttons = new ArrayList<>();
        buttons.add(button("chest", "Migrate Minigames", "Preview import from the old plugin data directory", () -> migration(p)));
        buttons.add(button("emerald", "Create arena", "Choose a game mode", () -> create(p)));
        for (Arena a : all.stream().skip(selected * 10L).limit(10).toList()) buttons.add(button("map", a.name + (a.enabled ? " · Enabled" : " · Disabled"), "Configure " + a.id, () -> edit(p, fresh(a.id))));
        if (selected > 0) buttons.add(button("arrow", "Previous", "Previous page", () -> admin(p, selected - 1)));
        if ((selected + 1) * 10 < all.size()) buttons.add(button("arrow", "Next", "Next page", () -> admin(p, selected + 1)));
        buttons.add(button("arrow", "Back", "Return to games", () -> home(p, 0)));
        show(p, "Manage Lumigames", "Set up arenas while disabled. All changes are saved immediately.", buttons);
    }
    void migration(Player p) {
        plugin.commands.adminPermission(p);
        try {
            LegacyMigration migration = new LegacyMigration(plugin.store);
            LegacyMigration.Plan plan = migration.preview(migration.defaultSource());
            plan.lines().forEach(plugin.getLogger()::info);
            List<Button> buttons = new ArrayList<>();
            if (!plan.arenas().isEmpty()) buttons.add(button("emerald", "Import " + plan.arenas().size() + " arenas", "Back up current arenas and add imported arenas as disabled", () -> {
                // Re-read source files and recheck current IDs instead of applying a stale preview.
                command(p, "admin migrate apply"); admin(p, 0);
            }));
            buttons.add(button("arrow", "Back", "Cancel import", () -> admin(p, 0)));
            show(p, "Migrate Minigames", plan.arenas().size() + " new arenas; " + (plan.entries().size() - plan.arenas().size()) + " skipped."
                + "\nSource: " + plan.source() + "\nImported arenas start disabled. Existing arenas and original files are preserved."
                + "\nReview the full warning report in the server console. For another source, use /lg admin migrate preview <path>.", buttons);
        } catch (java.io.IOException e) { plugin.error(p, e.getMessage()); }
    }
    void create(Player p) {
        plugin.commands.adminPermission(p);
        List<Button> buttons = new ArrayList<>();
        for (Arena.Mode mode : Arena.Mode.values()) buttons.add(button("slime_ball", mode.name(), "Create a " + mode.name().toLowerCase(Locale.ROOT) + " arena", () -> input(p, "Create " + mode, "Arena ID", "", 32, value -> command(p, "admin create " + value + " " + mode))));
        show(p, "Choose game mode", "Solo courses, races, combat arenas, team games, treasure hunts and custom objectives.", buttons);
    }
    void edit(Player p, Arena a) {
        plugin.commands.adminPermission(p);
        List<Button> buttons = new ArrayList<>();
        buttons.add(button("name_tag", "Name & description", "Edit the arena's display text", () -> textOptions(p, fresh(a.id))));
        buttons.add(button("compass", "Set positions", "Save your current position as a spawn or boundary", () -> positions(p, fresh(a.id))));
        buttons.add(button("repeater", "Game rules", "Player counts, timers, lives, scoring and protection rules", () -> rules(p, fresh(a.id))));
        buttons.add(button("light_weighted_pressure_plate", "Add checkpoint", "Stand on its checkpoint plate before selecting", () -> input(p, "Add checkpoint", "Checkpoint ID", "checkpoint" + (a.checkpoints.size() + 1), 32, value -> editAction(p, a.id, "checkpoint " + a.id + " " + value))));
        buttons.add(button("stone_pressure_plate", "Link checkpoint plate", "Look at a plate; reuse a checkpoint ID to add another respawn plate for that stage", () -> input(p, "Link checkpoint plate", "Checkpoint ID (existing or new)", "checkpoint" + (a.checkpoints.size() + 1), 32, value -> editAction(p, a.id, "link-checkpoint " + a.id + " " + value))));
        buttons.add(button("chest", "Save loadout", "Copy your inventory and armor as the arena loadout", () -> editAction(p, a.id, "loadout " + a.id)));
        buttons.add(button(a.enabled ? "redstone_torch" : "torch", a.enabled ? "Disable arena" : "Enable arena", "Enable only after all setup checks pass", () -> editAction(p, a.id, (a.enabled ? "disable " : "enable ") + a.id)));
        buttons.add(button("barrier", "Stop match", "Return all players and restore arena blocks", () -> editAction(p, a.id, "stop " + a.id)));
        buttons.add(button("lava_bucket", "Delete arena", "Confirm removal of the arena definition", () -> confirm(p, "Delete " + a.name + "?", "Remove this arena definition? Player statistics and saved progress remain on disk.", () -> { command(p, "admin delete " + a.id + " confirm"); admin(p, 0); }, () -> edit(p, fresh(a.id)))));
        buttons.add(button("arrow", "Back", "Arena list", () -> admin(p, 0)));
        show(p, "Manage " + a.name, "ID: " + a.id + " · " + a.mode + "\n" + (a.problems().isEmpty() ? "Setup complete." : String.join("\n", a.problems())) + "\nCheckpoints: " + a.checkpoints.size(), buttons);
    }
    void editAction(Player p, String id, String args) { command(p, "admin " + args); if (plugin.store.arenas.containsKey(id)) edit(p, fresh(id)); }
    void positions(Player p, Arena a) {
        List<Button> buttons = new ArrayList<>();
        for (String position : GameCommands.POSITIONS) buttons.add(button("compass", position, position.equals("treasure") ? "Add the block you are looking at" : "Save where you are standing", () -> editAction(p, a.id, "set " + a.id + " " + position)));
        buttons.add(button("arrow", "Back", "Arena settings", () -> edit(p, fresh(a.id))));
        show(p, "Positions · " + a.name, "Stand at the desired location, then select its purpose. Set a cuboid large enough to contain the whole playable area.", buttons);
    }
    void textOptions(Player p, Arena a) {
        show(p, "Display text · " + a.name, a.description, List.of(
            button("name_tag", "Name", "Edit display name", () -> input(p, "Display name", "Name", a.name, 64, value -> editAction(p, a.id, "option " + a.id + " name " + value))),
            button("book", "Description", "Edit game instructions", () -> input(p, "Instructions", "Description", a.description, 500, value -> editAction(p, a.id, "option " + a.id + " description " + value))),
            button("arrow", "Back", "Arena settings", () -> edit(p, fresh(a.id)))));
    }
    void rules(Player p, Arena a) {
        List<Button> buttons = new ArrayList<>();
        Map<String, Integer> numbers = new LinkedHashMap<>(); numbers.put("min-players", a.minPlayers); numbers.put("max-players", a.maxPlayers); numbers.put("duration", a.duration); numbers.put("countdown", a.countdown); numbers.put("lives", a.lives); numbers.put("target", a.target);
        numbers.forEach((key, value) -> buttons.add(button("clock", key + " · " + (key.equals("duration") && value == 0 ? "Unlimited" : value), key.equals("duration") ? "Seconds; use 0 for no time limit" : "Edit numeric rule", () -> input(p, key, "Value", value.toString(), 6, text -> editAction(p, a.id, "option " + a.id + " " + key + " " + text)))));
        Map<String, Boolean> flags = new LinkedHashMap<>(); flags.put("teams", a.teams); flags.put("pvp", a.pvp); flags.put("break-blocks", a.breakBlocks); flags.put("place-blocks", a.placeBlocks); flags.put("late-join", a.lateJoin); flags.put("flight", a.flight); flags.put("paintball", a.paintball); flags.put("infinite-ammo", a.infiniteAmmo); flags.put("require-permission", a.requirePermission);
        flags.forEach((key, value) -> buttons.add(button("lever", key + " · " + value, "Toggle this rule", () -> editAction(p, a.id, "option " + a.id + " " + key + " " + !value))));
        buttons.add(button("arrow", "Back", "Arena settings", () -> edit(p, fresh(a.id))));
        show(p, "Rules · " + a.name, "Duration and countdown are in seconds. Set duration to 0 for no time limit. Target is the winning score. Late joins are disabled for elimination, infection and race games.", buttons);
    }
    void confirm(Player p, String title, String message, Runnable yes, Runnable no) {
        show(p, title, message, List.of(button("redstone", "Confirm", "Apply this change", yes), button("arrow", "Cancel", "Keep the arena", no)));
    }
    void input(Player p, String title, String prompt, String initial, int max, Consumer<String> submit) {
        plugin.commands.adminPermission(p);
        UUID token = UUID.randomUUID(); views.put(p.getUniqueId(), token);
        ActionButton save = ActionButton.create(label("emerald", "Save"), Component.text("Save this value"), 200, DialogAction.customClick((response, audience) -> {
            if (!(audience instanceof Player actor) || !actor.getUniqueId().equals(p.getUniqueId())) return;
            String value = Objects.toString(response.getText("value"), "").strip();
            plugin.sync(() -> {
                if (!actor.isOnline() || !token.equals(views.get(actor.getUniqueId()))) return;
                views.remove(actor.getUniqueId());
                try { plugin.commands.adminPermission(actor); submit.accept(value); } catch (IllegalArgumentException e) { plugin.error(actor, e.getMessage()); }
            });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()));
        p.showDialog(Dialog.create(f -> f.empty().base(DialogBase.builder(Component.text(title)).pause(false)
            .body(List.of(DialogBody.plainMessage(Component.text("Escape cancels without saving."), 350)))
            .inputs(List.of(DialogInput.text("value", Component.text(prompt)).initial(initial).maxLength(max).build())).build())
            .type(DialogType.multiAction(List.of(save), ActionButton.create(label("barrier", "Cancel"), null, 200, null), 1))));
    }
}
