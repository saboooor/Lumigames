package ca.saboor.lumigames;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegacyMigrationTest {
    @TempDir Path dir;
    Path source;
    Store store;
    Lumigames plugin;
    MockedStatic<Bukkit> bukkit;
    World world;
    @BeforeEach void setup() throws Exception {
        source = dir.resolve("Minigames"); Files.createDirectories(source);
        plugin = mock(Lumigames.class); when(plugin.getDataFolder()).thenReturn(dir.resolve("Lumigames").toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        store = new Store(plugin); store.load(); plugin.store = store; plugin.engine = new GameEngine(plugin, store);
        world = mock(World.class); when(world.getName()).thenReturn("world"); when(world.getUID()).thenReturn(UUID.randomUUID());
        bukkit = mockStatic(Bukkit.class); bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
    }
    @AfterEach void close() { bukkit.close(); }
    Path config(String name, String yaml) throws Exception {
        Path file = source.resolve("minigames").resolve(name).resolve("config.yml"); Files.createDirectories(file.getParent()); Files.writeString(file, yaml); return file;
    }
    String arena(String name) {
        return name + """
            :
              type: MULTIPLAYER
              scoretype: kills
              enabled: true
              displayName: Old Arena
              objective: Score ten kills
              minplayers: 3
              maxplayers: 12
              timer: 900
              maxscore: 10
              startWaitTime: 7
              lives: 2.0
              startpos:
                '0': {world: world, x: 2.5, y: 65, z: 3.5, yaw: 90, pitch: 10}
              lobbypos: {world: world, x: 1, y: 64, z: 1}
              quitpos: {world: world, x: 100, y: 70, z: 100}
              spectatorpos: {world: world, x: 5, y: 75, z: 5}
              regenRegions:
                arena: {name: arena, world: world, pos1: '0:60:0', pos2: '20:90:20'}
            """;
    }
    @Test void previewReadsRealLayoutAndMapsSettingsWithoutWriting() throws Exception {
        Path file = config("old", arena("Old.Arena")); String original = Files.readString(file);
        var plan = new LegacyMigration(store).preview(source); assertEquals(1, plan.arenas().size());
        Arena a = plan.arenas().getFirst(); assertEquals("old-arena", a.id); assertEquals("Old Arena", a.name); assertFalse(a.enabled);
        assertEquals(Arena.Mode.KILLS, a.mode); assertEquals(3, a.minPlayers); assertEquals(12, a.maxPlayers); assertEquals(900, a.duration); assertEquals(17, a.countdown); assertEquals(10, a.target);
        assertEquals(90, a.start.getYaw()); assertEquals(10, a.start.getPitch()); assertEquals(100, a.exit.getBlockX()); assertTrue(a.problems().isEmpty());
        assertFalse(Files.exists(dir.resolve("Lumigames"))); assertEquals(original, Files.readString(file));
    }
    @Test void applyBacksUpExactFilePreservesSourcesAndPersistsDisabledArenas() throws Exception {
        Path file = config("old", arena("old")); String original = Files.readString(file);
        Arena existing = new Arena("existing", Arena.Mode.SOLO); store.arenas.put(existing.id, existing); store.saveArenas();
        byte[] before = Files.readAllBytes(store.file("arenas.yml").toPath());
        LegacyMigration migration = new LegacyMigration(store); var result = migration.apply(migration.preview(source));
        assertEquals(1, result.imported()); assertArrayEquals(before, Files.readAllBytes(result.backup())); assertTrue(Files.exists(result.report()));
        assertEquals(original, Files.readString(file)); assertSame(existing, store.arenas.get("existing")); assertFalse(store.arenas.get("old").enabled);
        Store reload = new Store(plugin); reload.load(); assertEquals(Set.of("existing", "old"), reload.arenas.keySet()); assertEquals(900, reload.arenas.get("old").duration);
    }
    @Test void repeatImportSkipsExistingIds() throws Exception {
        config("old", arena("old")); LegacyMigration migration = new LegacyMigration(store); migration.apply(migration.preview(source));
        var repeat = migration.preview(source); assertTrue(repeat.arenas().isEmpty());
        assertTrue(repeat.lines().stream().anyMatch(line -> line.contains("without overwriting")));
        assertThrows(IllegalArgumentException.class, () -> migration.apply(repeat));
    }
    @Test void normalizedSourceIdCollisionsAreReportedInsteadOfOverwritten() throws Exception {
        config("a", arena("Old Arena")); config("b", arena("old-arena"));
        var plan = new LegacyMigration(store).preview(source); assertEquals(1, plan.arenas().size()); assertEquals(2, plan.entries().size());
    }
    @Test void worldMustBeLoadedAndFailedArenaCanBeRetried() throws Exception {
        config("old", arena("old").replace("world: world", "world: missing"));
        LegacyMigration migration = new LegacyMigration(store); var plan = migration.preview(source); assertTrue(plan.arenas().isEmpty());
        assertTrue(plan.lines().stream().anyMatch(line -> line.contains("not loaded")));
        bukkit.when(() -> Bukkit.getWorld("missing")).thenReturn(world); assertEquals(1, migration.preview(source).arenas().size());
    }
    @Test void malformedYamlDoesNotPreventOtherArenaImports() throws Exception {
        Path bad = config("broken", "broken: [unclosed"); config("good", arena("good"));
        var plan = new LegacyMigration(store).preview(source); assertEquals(1, plan.arenas().size()); assertEquals("good", plan.arenas().getFirst().id); assertTrue(Files.exists(bad));
    }
    @Test void duplicateYamlKeysAndJavaObjectTagsAreRejected() throws Exception {
        config("duplicate", "old:\n  type: MULTIPLAYER\n  type: SINGLEPLAYER\n");
        config("unsafe", "!!java.net.URL ['https://example.com']");
        assertTrue(new LegacyMigration(store).preview(source).arenas().isEmpty());
    }
    @Test void modernAndLegacyRegenerationBoundsAreSupported() throws Exception {
        String yaml = arena("old"); yaml = yaml.substring(0, yaml.indexOf("  regenRegions:")) + "  regenarea:\n    '1': {world: world, x: 0, y: 60, z: 0}\n    '2': {world: world, x: 20, y: 90, z: 20}\n";
        config("old", yaml); Arena a = new LegacyMigration(store).preview(source).arenas().getFirst();
        assertTrue(a.inside(a.start)); assertEquals(20, a.corner2.getBlockX());
    }
    @Test void multipleRegionsUseEnclosingCuboidAndReportGaps() throws Exception {
        config("old", arena("old") + "    extra: {name: extra, world: world, pos1: '30:60:30', pos2: '40:90:40'}\n");
        var plan = new LegacyMigration(store).preview(source); assertEquals(40, plan.arenas().getFirst().corner2.getBlockX());
        assertTrue(plan.lines().stream().anyMatch(line -> line.contains("gaps between regions")));
    }
    @Test void teamSpawnsAndFlagsNeedingManualSetupAreReported() throws Exception {
        config("ctf", arena("ctf").replace("scoretype: kills", "scoretype: ctf") + """
              teams:
                RED:
                  startpos:
                    '0': {world: world, x: 1, y: 65, z: 1}
                BLUE:
                  startpos:
                    '0': {world: world, x: 19, y: 65, z: 19}
            """);
        var plan = new LegacyMigration(store).preview(source); Arena a = plan.arenas().getFirst();
        assertTrue(a.teams); assertEquals(1, a.redSpawn.getBlockX()); assertEquals(19, a.blueSpawn.getBlockX());
        assertTrue(plan.lines().stream().anyMatch(line -> line.contains("red-flag")));
    }
    @Test void loadoutSlotsIncludingArmorAndOffhandAreConverted() throws Exception {
        config("old", arena("old") + """
              loadouts:
                Archer:
                  items:
                    '0': {type: BOW}
                    '100': {type: LEATHER_BOOTS}
                    '101': {type: LEATHER_LEGGINGS}
                    '102': {type: LEATHER_CHESTPLATE}
                    '103': {type: LEATHER_HELMET}
                    '-106': {type: SHIELD}
            """);
        List<String> types = new ArrayList<>(); ItemStack item = mock(ItemStack.class);
        var migration = new LegacyMigration(store, raw -> { types.add(raw.get("type").toString()); return item; });
        var plan = migration.preview(source); var contents = plan.arenas().getFirst().kits.get("archer");
        for (int slot : List.of(0, 36, 37, 38, 39, 40)) assertSame(item, contents[slot]);
        assertEquals(6, types.size()); assertTrue(plan.lines().stream().anyMatch(line -> line.contains("renamed to archer")));
    }
    @Test void legacyDirectLoadoutAndExtraLoadoutsAreSupported() throws Exception {
        config("old", arena("old") + """
              loadout:
                '0': {type: STONE_SWORD}
              extraloadouts:
                red:
                  '0': {type: BOW}
            """);
        ItemStack item = mock(ItemStack.class); var plan = new LegacyMigration(store, raw -> item).preview(source);
        Arena a = plan.arenas().getFirst(); assertSame(item, a.loadout[0]); assertSame(item, a.kits.get("red")[0]);
    }
    @Test void unreadableItemSkipsArenaInsteadOfDroppingEquipmentSilently() throws Exception {
        config("old", arena("old") + "  loadouts:\n    default:\n      items:\n        '0': {type: INVALID}\n");
        var plan = new LegacyMigration(store, raw -> { throw new IllegalArgumentException("Unsupported item"); }).preview(source);
        assertTrue(plan.arenas().isEmpty()); assertTrue(plan.lines().stream().anyMatch(line -> line.contains("could not be converted")));
    }
    @Test void unknownSettingsAndModuleFilesAreReportedAndNotExecuted() throws Exception {
        Path file = config("old", arena("old") + "  randomizechests: true\n  reward: {command: 'op somebody'}\n");
        Files.writeString(file.resolveSibling("regions.yml"), "scripts: [do something]\n");
        var plan = new LegacyMigration(store).preview(source); assertEquals(1, plan.arenas().size()); assertTrue(plan.arenas().getFirst().rewards.isEmpty());
        assertTrue(plan.lines().stream().anyMatch(line -> line.contains("randomizechests"))); assertTrue(plan.lines().stream().anyMatch(line -> line.contains("regions.yml")));
    }
    @Test void previewAcceptsSingleFileArenaDirectoryAndFlatLegacyFiles() throws Exception {
        Path file = config("old", arena("old")); LegacyMigration migration = new LegacyMigration(store);
        assertEquals(1, migration.preview(file).arenas().size()); assertEquals(1, migration.preview(file.getParent()).arenas().size());
        Path flat = dir.resolve("flat"); Files.createDirectories(flat); Files.writeString(flat.resolve("old.yml"), arena("old"));
        assertEquals(1, migration.preview(flat).arenas().size());
    }
    @Test void globalCountdownFallbackAndUnlimitedTimersHaveExplicitMapping() throws Exception {
        Files.writeString(source.resolve("config.yml"), "multiplayer: {waitforplayers: 20, startcountdown: 6}\n");
        config("old", arena("old").replace("  startWaitTime: 7\n", "").replace("timer: 900", "timer: 0"));
        var plan = new LegacyMigration(store).preview(source); assertEquals(26, plan.arenas().getFirst().countdown); assertEquals(0, plan.arenas().getFirst().duration);
        assertTrue(plan.lines().stream().anyMatch(line -> line.contains("unlimited/missing timer")));
    }
    @Test void failedCommitLeavesInMemoryArenasAndExistingDiskStateAlone() throws Exception {
        config("old", arena("old")); Files.createDirectories(store.file("arenas.yml").toPath());
        LegacyMigration migration = new LegacyMigration(store); var plan = migration.preview(source);
        assertThrows(IOException.class, () -> migration.apply(plan)); assertTrue(store.arenas.isEmpty()); assertTrue(Files.isDirectory(store.file("arenas.yml").toPath()));
    }
    @Test void stalePlanCannotOverwriteNewArena() throws Exception {
        config("old", arena("old")); LegacyMigration migration = new LegacyMigration(store); var plan = migration.preview(source);
        Arena a = new Arena("old", Arena.Mode.SOLO); store.arenas.put("old", a);
        assertThrows(IllegalArgumentException.class, () -> migration.apply(plan)); assertSame(a, store.arenas.get("old"));
    }
    @Test void consoleCanPreviewAndApplyUsingDefaultDataFolder() throws Exception {
        config("old", arena("old")); ConsoleCommandSender sender = mock(ConsoleCommandSender.class); when(sender.hasPermission("lumigames.admin")).thenReturn(true);
        GameCommands commands = new GameCommands(plugin);
        commands.onCommand(sender, null, "lg", new String[]{"admin", "migrate", "preview"}); assertTrue(store.arenas.isEmpty());
        commands.onCommand(sender, null, "lg", new String[]{"admin", "migrate", "apply"}); assertTrue(store.arenas.containsKey("old"));
    }
    @Test void migrationPermissionIsCheckedBeforeReadingSource() throws Exception {
        Player player = mock(Player.class); GameCommands commands = new GameCommands(plugin);
        commands.onCommand(player, null, "lg", new String[]{"admin", "migrate", "apply"});
        verify(player).sendMessage("Migration failed: You cannot migrate arenas."); assertFalse(Files.exists(dir.resolve("Lumigames")));
    }
    @Test void modeMappingSupportsLegacyPresets() {
        List<String> notes = new ArrayList<>();
        assertEquals(Arena.Mode.SOLO, LegacyMigration.mode(Map.of("type", "SINGLEPLAYER"), notes));
        assertEquals(Arena.Mode.SPLEEF, LegacyMigration.mode(Map.of("type", "MULTIPLAYER", "whitelistblocks", List.of("SNOW_BLOCK")), notes));
        assertEquals(Arena.Mode.LIVES, LegacyMigration.mode(Map.of("type", "MULTIPLAYER", "lives", 1), notes));
        assertEquals(Arena.Mode.TREASURE, LegacyMigration.mode(Map.of("scoretype", "treasurehunt"), notes));
        assertEquals(Arena.Mode.CUSTOM, LegacyMigration.mode(Map.of("type", "MULTIPLAYER", "scoretype", "custom-addon"), notes));
        assertTrue(notes.stream().anyMatch(note -> note.contains("Unknown mechanic")));
    }
    @Test void metadataDecodesOnlyWhitelistedBuiltinObjectsAndRejectsUnknownClasses() {
        Object decoded = LegacyMigration.decodeMetadata(Map.of("==", "Color", "RED", 12, "GREEN", 34, "BLUE", 56));
        assertEquals(Color.fromRGB(12, 34, 56), decoded);
        Object nested = LegacyMigration.decodeMetadata(Map.of("colors", List.of(Map.of("==", "Color", "RED", 12, "GREEN", 34, "BLUE", 56))));
        assertEquals(List.of(decoded), ((Map<?, ?>) nested).get("colors"));
        assertThrows(IllegalArgumentException.class, () -> LegacyMigration.decodeMetadata(Map.of("==", "evil.PluginClass")));
        assertThrows(IllegalArgumentException.class, () -> LegacyMigration.readItem(Map.of("type", "STONE", "meta", Map.of("==", "evil.PluginClass"))));
    }
    @Test void potionEffectsAreMappedWithTheirAmplifierAndDuration() throws Exception {
        config("old", arena("old") + "  loadouts:\n    default:\n      potions:\n        SPEED: {dur: 1200, amp: 1}\n");
        var plan = new LegacyMigration(store).preview(source); assertEquals(1, plan.arenas().size());
        var effect = plan.arenas().getFirst().kitEffects.get("default").getFirst();
        assertEquals(1200, effect.getDuration()); assertEquals(1, effect.getAmplifier());
    }
    @Test void multipleStartPositionsUseNumericOrderAndReportReduction() throws Exception {
        var notes = new ArrayList<String>();
        Map<String, Object> locations = new LinkedHashMap<>();
        locations.put("10", Map.of("world", "world", "x", 10, "y", 64, "z", 0));
        locations.put("2", Map.of("world", "world", "x", 2, "y", 64, "z", 0));
        assertEquals(2, new LegacyMigration(store).firstLocation(locations, "startpos", notes).getBlockX());
        assertTrue(notes.stream().anyMatch(note -> note.contains("only the first")));
    }
    @Test void pathsContainingSpacesAreAcceptedByConsoleCommand() throws Exception {
        Path custom = dir.resolve("backup with spaces.yml"); Files.writeString(custom, arena("old"));
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class); when(sender.hasPermission("lumigames.admin")).thenReturn(true);
        List<String> args = new ArrayList<>(List.of("admin", "migrate", "apply")); args.addAll(Arrays.asList(custom.toString().split(" ")));
        new GameCommands(plugin).onCommand(sender, null, "lg", args.toArray(String[]::new));
        assertTrue(store.arenas.containsKey("old"));
    }

}
