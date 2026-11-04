package ca.saboor.lumigames;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GameEngineTest {
    @TempDir Path dir;
    Lumigames plugin;
    Store store;
    GameEngine engine;
    World world;
    MockedStatic<Bukkit> bukkit;
    Arena arena;
    @BeforeEach void setup() throws Exception {
        world = mock(World.class); when(world.getName()).thenReturn("world"); when(world.getUID()).thenReturn(UUID.randomUUID());
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
        plugin = mock(Lumigames.class); when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration()); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        store = new Store(plugin); store.load(); engine = new GameEngine(plugin, store);
        plugin.store = store; plugin.engine = engine;
        arena = new Arena("parkour", Arena.Mode.SOLO);
        arena.corner1 = new Location(world, 0, 0, 0); arena.corner2 = new Location(world, 20, 100, 20);
        arena.start = new Location(world, 1.5, 65, 1.5); arena.enabled = true;
        store.arenas.put(arena.id, arena);
    }
    @AfterEach void close() { bukkit.close(); }
    Player player() {
        Player p = mock(Player.class); UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id); when(p.getName()).thenReturn("Player");
        when(p.hasPermission(anyString())).thenReturn(true); when(p.isOnline()).thenReturn(true);
        when(p.getLocation()).thenReturn(new Location(world, 50, 70, 50)); when(p.getWorld()).thenReturn(world);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL); when(p.getHealth()).thenReturn(16.0);
        when(p.getFoodLevel()).thenReturn(17); when(p.getSaturation()).thenReturn(4f);
        when(p.getFlySpeed()).thenReturn(.1f); when(p.getWalkSpeed()).thenReturn(.2f);
        when(p.getRemainingAir()).thenReturn(300); when(p.getActivePotionEffects()).thenReturn(List.of());
        when(p.getPassengers()).thenReturn(List.of());
        AttributeInstance health = mock(AttributeInstance.class); when(health.getValue()).thenReturn(20.0);
        when(p.getAttribute(Attribute.MAX_HEALTH)).thenReturn(health);
        PlayerInventory inventory = mock(PlayerInventory.class); when(p.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[41]);
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(p);
        return p;
    }
    @Test void joinPersistsRecoveryBeforeInventoryIsCleared() throws Exception {
        Player p = player();
        PlayerInventory inventory = p.getInventory();
        doAnswer(call -> { assertTrue(engine.recovery(p.getUniqueId()).exists()); return null; }).when(inventory).clear();
        engine.join(p, arena, false);
        assertNotNull(engine.session(p)); assertTrue(engine.recovery(p.getUniqueId()).exists());
        var yaml = DataFiles.read(engine.recovery(p.getUniqueId()));
        assertEquals(16, yaml.getDouble("snapshot.health"));
        assertEquals(50, yaml.getLocation("return").getBlockX());
    }
    @Test void leaveRestoresOriginalLocationInventoryAndPlayerData() {
        Player p = player(); engine.join(p, arena, false); clearInvocations(p, p.getInventory());
        engine.leave(p);
        assertNull(engine.session(p)); assertFalse(engine.recovery(p.getUniqueId()).exists());
        verify(p).teleport(new Location(world, 50, 70, 50), TeleportCause.PLUGIN);
        verify(p.getInventory()).setContents(any()); verify(p).setGameMode(GameMode.SURVIVAL); verify(p).setHealth(16);
        verify(p).saveData();
    }
    @Test void blockedReturnRetainsRecoveryAndRetries() {
        Player p = player(); engine.join(p, arena, false);
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(false);
        engine.leave(p);
        assertTrue(engine.session(p).restoring); assertTrue(engine.recovery(p.getUniqueId()).exists());
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(true);
        engine.tick(); assertNull(engine.session(p)); assertFalse(engine.recovery(p.getUniqueId()).exists());
    }
    @Test void disconnectWithFailedReturnIsRecoveredAfterRestart() throws Exception {
        Player p = player(); engine.join(p, arena, false);
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(false);
        engine.disconnect(p); assertNull(engine.session(p)); assertTrue(engine.recovery(p.getUniqueId()).exists());
        GameEngine restarted = new GameEngine(plugin, store);
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(true);
        restarted.recover(p); assertFalse(restarted.recovery(p.getUniqueId()).exists()); verify(p).setHealth(16);
    }
    @Test void failedRecoverySaveNeverChangesInventory() throws Exception {
        Player p = player(); Files.writeString(dir.resolve("recovery"), "not a directory");
        engine.join(p, arena, false);
        assertNull(engine.session(p)); verify(p.getInventory(), never()).clear();
        verify(p, never()).teleport(any(Location.class), any(TeleportCause.class));
    }
    @Test void duplicateJoinCannotOverwriteOriginalSnapshot() throws Exception {
        Player p = player(); engine.join(p, arena, false);
        String recovery = Files.readString(engine.recovery(p.getUniqueId()).toPath());
        engine.join(p, arena, false);
        assertEquals(recovery, Files.readString(engine.recovery(p.getUniqueId()).toPath()));
    }
    @Test void checkpointsAreOrderedPersistedAndResumed() throws Exception {
        Player p = player(); arena.checkpoints.put("one", new Location(world, 3, 65, 3)); arena.checkpoints.put("two", new Location(world, 5, 65, 5));
        engine.join(p, arena, false); engine.checkpoint(p, "two"); assertNull(engine.session(p).checkpoint);
        engine.checkpoint(p, "one"); assertEquals("one", store.checkpoint(p.getUniqueId(), arena.id));
        engine.checkpoint(p, "one"); assertEquals("one", engine.session(p).checkpoint);
        engine.leave(p);
        Store reloaded = new Store(plugin); reloaded.load(); assertEquals("one", reloaded.checkpoint(p.getUniqueId(), arena.id));
        engine.join(p, arena, false); assertEquals(new Location(world, 3, 65, 3), engine.spawn(engine.session(p)));
    }
    @Test void finishCannotSkipCheckpointsAndClearsProgressAfterWin() {
        Player p = player(); arena.checkpoints.put("one", new Location(world, 3, 65, 3));
        engine.join(p, arena, false); engine.finish(p); assertNotNull(engine.session(p)); assertEquals(0, store.stat(p.getUniqueId(), arena.id, "wins"));
        engine.checkpoint(p, "one"); engine.finish(p);
        assertNull(engine.session(p)); assertEquals(1, store.stat(p.getUniqueId(), arena.id, "wins")); assertNull(store.checkpoint(p.getUniqueId(), arena.id));
        engine.finish(p); assertEquals(1, store.stat(p.getUniqueId(), arena.id, "wins"));
    }
    @Test void winningWithBlockedReturnCompletesAfterRetry() {
        Player p = player(); engine.join(p, arena, false);
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(false);
        engine.finish(p); assertEquals(0, store.stat(p.getUniqueId(), arena.id, "wins"));
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(true);
        engine.tick(); assertEquals(1, store.stat(p.getUniqueId(), arena.id, "wins"));
    }
    @Test void deathDefersRestorationUntilRespawn() {
        Player p = player(); engine.join(p, arena, false); when(p.isDead()).thenReturn(true);
        engine.leave(p); assertTrue(engine.recovery(p.getUniqueId()).exists()); assertTrue(engine.session(p).restoring);
        when(p.isDead()).thenReturn(false); engine.recover(p); assertNull(engine.session(p));
    }
    @Test void raceDoesNotResumeSavedCheckpoints() throws Exception {
        arena.mode = Arena.Mode.RACE; arena.minPlayers = 2; arena.lobby = arena.start;
        Player p = player(); store.checkpoint(p.getUniqueId(), arena.id, "one", 1000);
        arena.checkpoints.put("one", new Location(world, 3, 65, 3));
        engine.join(p, arena, false); assertNull(engine.session(p).checkpoint);
    }
    @Test void countdownCancelsWhenTooFewPlayersRemain() {
        arena.mode = Arena.Mode.KILLS; arena.minPlayers = 2; arena.lobby = arena.start;
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false);
        engine.tick(); assertEquals(Match.Phase.COUNTDOWN, engine.match(arena).phase);
        engine.leave(b); engine.tick(); assertEquals(Match.Phase.WAITING, engine.match(arena).phase);
    }
    @Test void lastManStandingWinsWhenOpponentDisconnects() {
        arena.mode = Arena.Mode.LIVES; arena.minPlayers = 2; arena.lobby = arena.start;
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false);
        engine.start(engine.match(arena)); engine.disconnect(b); engine.tick();
        assertNull(engine.session(a)); assertEquals(1, store.stat(a.getUniqueId(), arena.id, "wins"));
    }
    @Test void observersDoNotCountTowardsStart() {
        arena.mode = Arena.Mode.KILLS; arena.minPlayers = 2; arena.lobby = arena.start;
        Player a = player(), observer = player(); engine.join(a, arena, false); engine.join(observer, arena, true);
        engine.tick(); assertEquals(Match.Phase.WAITING, engine.match(arena).phase);
    }
    @Test void fullMatchRejectsExtraPlayers() {
        arena.maxPlayers = 1; Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false);
        assertNull(engine.session(b)); assertFalse(engine.recovery(b.getUniqueId()).exists());
    }
    @Test void disabledAndIncompleteArenasRejectPlayers() {
        Player p = player(); arena.enabled = false; engine.join(p, arena, false); assertNull(engine.session(p));
        arena.enabled = true; arena.start = null; engine.join(p, arena, false); assertNull(engine.session(p));
    }
    @Test void soloRestartClearsCheckpointAndTimer() throws Exception {
        Player p = player(); arena.checkpoints.put("one", new Location(world, 3, 65, 3));
        engine.join(p, arena, false); engine.checkpoint(p, "one"); engine.session(p).previousElapsed = 99999;
        new GameCommands(plugin).restart(p);
        assertNull(engine.session(p).checkpoint); assertNull(store.checkpoint(p.getUniqueId(), arena.id)); assertTrue(engine.session(p).elapsed() < 1000);
    }
    @Test void deletedSavedCheckpointDoesNotKeepExpiredTimer() throws Exception {
        Player p = player(); store.checkpoint(p.getUniqueId(), arena.id, "deleted", 99999999);
        engine.join(p, arena, false); assertNull(engine.session(p).checkpoint); assertTrue(engine.session(p).elapsed() < 1000);
    }
    @Test void killTargetEndsMatchAndRestoresEveryone() {
        arena.mode = Arena.Mode.KILLS; arena.minPlayers = 2; arena.lobby = arena.start; arena.target = 1;
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false); engine.start(engine.match(arena));
        engine.died(b, a);
        assertNull(engine.session(a)); assertNull(engine.session(b)); assertFalse(engine.matches.containsKey(arena.id));
        assertEquals(1, store.stat(a.getUniqueId(), arena.id, "kills")); assertEquals(1, store.stat(a.getUniqueId(), arena.id, "wins"));
    }
    @Test void infectionTurnsLastSurvivorAndResolvesMatch() {
        arena.mode = Arena.Mode.INFECTION; arena.teams = true; arena.minPlayers = 2; arena.lobby = arena.start;
        arena.redSpawn = arena.start; arena.blueSpawn = new Location(world, 2, 65, 2);
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false); engine.start(engine.match(arena));
        assertEquals("red", engine.session(a).team); assertEquals("blue", engine.session(b).team);
        engine.died(b, a); assertFalse(engine.matches.containsKey(arena.id)); assertNull(engine.session(a));
    }
    @Test void teamKillsDoNotScore() {
        arena.mode = Arena.Mode.KILLS; arena.teams = true; arena.minPlayers = 2; arena.lobby = arena.start;
        arena.redSpawn = arena.start; arena.blueSpawn = arena.start;
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false); engine.start(engine.match(arena));
        engine.session(b).team = engine.session(a).team;
        engine.died(b, a); assertEquals(0, engine.session(a).score);
    }
    @Test void arenaPermissionIsCheckedBeforeSnapshotCreation() {
        Player p = player(); arena.requirePermission = true; when(p.hasPermission("lumigames.arena.parkour")).thenReturn(false);
        engine.join(p, arena, false); assertNull(engine.session(p)); assertFalse(engine.recovery(p.getUniqueId()).exists());
    }
    @Test void invalidSnapshotIsKeptInsteadOfRestoringAnEmptyInventory() throws Exception {
        Player p = player(); var yaml = new YamlConfiguration(); yaml.createSection("snapshot").set("location", p.getLocation());
        DataFiles.save(yaml, engine.recovery(p.getUniqueId()));
        engine.recover(p); assertTrue(engine.recovery(p.getUniqueId()).exists()); verify(p.getInventory(), never()).setContents(any());
    }

    @Test void ctfRequiresOwnFlagAtHomeAndScoresDelivery() {
        arena.mode = Arena.Mode.CTF; arena.teams = true; arena.minPlayers = 2; arena.lobby = arena.start; arena.target = 2;
        arena.redSpawn = arena.start; arena.blueSpawn = new Location(world, 15, 65, 15);
        arena.redFlag = arena.redSpawn; arena.blueFlag = arena.blueSpawn;
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false); engine.start(engine.match(arena));
        GameListener listener = new GameListener(plugin);
        listener.flag(a, engine.session(a), arena.blueFlag); assertTrue(engine.session(a).carryingFlag);
        listener.flag(b, engine.session(b), arena.redFlag); assertTrue(engine.session(b).carryingFlag);
        listener.flag(a, engine.session(a), arena.redFlag); assertEquals(0, engine.session(a).score);
        engine.session(b).carryingFlag = false;
        listener.flag(a, engine.session(a), arena.redFlag); assertEquals(1, engine.session(a).score); assertFalse(engine.session(a).carryingFlag);
    }
    @Test void externalTeleportIsCancelledAndSchedulesLeave() {
        Player p = player(); engine.join(p, arena, false);
        var event = mock(org.bukkit.event.player.PlayerTeleportEvent.class); when(event.getPlayer()).thenReturn(p);
        new GameListener(plugin).teleport(event); verify(event).setCancelled(true); verify(plugin).sync(any(Runnable.class));
    }
    @Test void gameCommandsCannotEscapeThroughOtherPlugins() {
        Player p = player(); engine.join(p, arena, false);
        var event = mock(org.bukkit.event.player.PlayerCommandPreprocessEvent.class); when(event.getPlayer()).thenReturn(p); when(event.getMessage()).thenReturn("/minecraft:give Player diamond 64");
        new GameListener(plugin).command(event); verify(event).setCancelled(true);
        clearInvocations(event); when(event.getMessage()).thenReturn("/lg leave");
        new GameListener(plugin).command(event); verify(event, never()).setCancelled(true);
    }
    @Test void damageFromOutsideCannotAffectParticipants() {
        Player p = player(), outsider = player(); engine.join(p, arena, false);
        var event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class); when(event.getEntity()).thenReturn(p); when(event.getDamager()).thenReturn(outsider);
        new GameListener(plugin).damage(event); verify(event).setCancelled(true);
    }
    @Test void participantsCannotDamageOutsiders() {
        Player p = player(), outsider = player(); engine.join(p, arena, false);
        var event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class); when(event.getEntity()).thenReturn(outsider); when(event.getDamager()).thenReturn(p);
        new GameListener(plugin).damage(event); verify(event).setCancelled(true);
    }
    @Test void spectatorsAreProtectedFromDamage() {
        arena.mode = Arena.Mode.KILLS; arena.minPlayers = 2; arena.lobby = arena.start;
        Player p = player(); engine.join(p, arena, true);
        var event = mock(org.bukkit.event.entity.EntityDamageEvent.class); when(event.getEntity()).thenReturn(p);
        new GameListener(plugin).damage(event); verify(event).setCancelled(true);
    }
    @Test void checkpointWriteFailureDoesNotAdvanceInMemoryProgress() throws Exception {
        Player p = player(); arena.checkpoints.put("one", new Location(world, 3, 65, 3)); engine.join(p, arena, false);
        Files.createDirectory(dir.resolve("progress.yml"));
        engine.checkpoint(p, "one"); assertNull(engine.session(p).checkpoint); assertNull(store.checkpoint(p.getUniqueId(), arena.id));
    }

    Location[] linkedPlates() {
        Location first = new Location(world, 3.5, 65.1, 3.5), second = new Location(world, 6.5, 65.1, 3.5);
        arena.checkpoints.put("one", first); arena.checkpointPlates.put("one", new ArrayList<>(List.of(first, second)));
        return new Location[]{first, second};
    }
    @Test void alternatePlatesChangeSpawnWithoutAdvancingCheckpoint() {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false);
        engine.checkpoint(p, "one", plates[0]); assertEquals(plates[0], engine.spawn(engine.session(p)));
        engine.checkpoint(p, "one", plates[1]); assertEquals("one", engine.session(p).checkpoint); assertEquals(plates[1], engine.spawn(engine.session(p)));
        engine.revert(p); verify(p).teleport(plates[1], TeleportCause.PLUGIN);
        engine.checkpoint(p, "one", plates[0]); assertEquals(plates[0], engine.spawn(engine.session(p)));
    }
    @Test void selectedPlatePersistsAcrossLeaveReloadAndRejoin() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false); engine.checkpoint(p, "one", plates[1]); engine.leave(p);
        Store loaded = new Store(plugin); loaded.load(); assertEquals(plates[1], loaded.checkpointSpawn(p.getUniqueId(), arena.id));
        GameEngine restarted = new GameEngine(plugin, loaded); loaded.arenas.put(arena.id, arena);
        restarted.join(p, arena, false); assertEquals(plates[1], restarted.spawn(restarted.session(p)));
    }
    @Test void checkpointPlateCannotSkipForwardOrMoveBackToOlderStage() {
        Location[] plates = linkedPlates(); Location last = new Location(world, 9.5, 65.1, 3.5);
        arena.checkpoints.put("two", last); arena.checkpointPlates.put("two", List.of(last));
        Player p = player(); engine.join(p, arena, false);
        engine.checkpoint(p, "two", last); assertNull(engine.session(p).checkpoint);
        engine.checkpoint(p, "one", plates[1]); engine.checkpoint(p, "two", last); engine.checkpoint(p, "one", plates[0]);
        assertEquals("two", engine.session(p).checkpoint); assertEquals(last, engine.spawn(engine.session(p)));
    }
    @Test void removingSelectedPlateFallsBackToRemainingCheckpointSpawn() {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false); engine.checkpoint(p, "one", plates[1]);
        arena.checkpointPlates.get("one").remove(plates[1]); assertEquals(plates[0], engine.spawn(engine.session(p)));
    }
    @Test void writeFailureDoesNotSwitchTheSelectedPlate() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false); engine.checkpoint(p, "one", plates[0]);
        java.nio.file.Files.delete(dir.resolve("progress.yml")); java.nio.file.Files.createDirectory(dir.resolve("progress.yml"));
        engine.checkpoint(p, "one", plates[1]); assertEquals(plates[0], engine.spawn(engine.session(p))); assertEquals(plates[0], store.checkpointSpawn(p.getUniqueId(), arena.id));
    }
    @Test void restartAndWinClearSelectedPlate() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false); engine.checkpoint(p, "one", plates[1]);
        new GameCommands(plugin).restart(p); assertNull(engine.session(p).checkpointSpawn); assertNull(store.checkpointSpawn(p.getUniqueId(), arena.id));
        engine.checkpoint(p, "one", plates[1]); engine.finish(p); assertNull(store.checkpointSpawn(p.getUniqueId(), arena.id));
    }
    @Test void physicalPressurePlateEventSupportsAnyLinkedPlateMaterial() {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false);
        var block = mock(org.bukkit.block.Block.class); when(block.getType()).thenReturn(Material.STONE_PRESSURE_PLATE); when(block.getLocation()).thenReturn(plates[1]);
        var event = mock(org.bukkit.event.player.PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(p); when(event.getAction()).thenReturn(org.bukkit.event.block.Action.PHYSICAL); when(event.getClickedBlock()).thenReturn(block);
        new GameListener(plugin).interact(event); assertEquals(plates[1], engine.spawn(engine.session(p)));
    }
    @Test void linkCommandsAddSeveralPlatesAndRejectCrossCheckpointLinks() throws Exception {
        Player admin = player(); var block = mock(org.bukkit.block.Block.class); when(block.getType()).thenReturn(Material.OAK_PRESSURE_PLATE);
        when(block.getLocation()).thenReturn(new Location(world, 3, 65, 3)); when(admin.getTargetBlockExact(6)).thenReturn(block);
        GameCommands commands = new GameCommands(plugin); commands.admin(admin, new String[]{"link-checkpoint", arena.id, "one"});
        assertEquals(1, arena.checkpointPlates.get("one").size()); assertEquals(new Location(world, 3.5, 65.1, 3.5), arena.checkpoints.get("one"));
        when(block.getLocation()).thenReturn(new Location(world, 6, 65, 3)); commands.admin(admin, new String[]{"link-checkpoint", arena.id, "one"});
        assertEquals(2, arena.checkpointPlates.get("one").size());
        assertThrows(IllegalArgumentException.class, () -> commands.admin(admin, new String[]{"link-checkpoint", arena.id, "two"}));
        assertEquals(1, store.arenas.get(arena.id).checkpoints.size());
    }
    @Test void unlinkLastPlateStopsActivationButPreservesSignCheckpoint() throws Exception {
        Player admin = player(); Location[] plates = linkedPlates(); arena.checkpointPlates.get("one").remove(plates[1]);
        var block = mock(org.bukkit.block.Block.class); when(block.getType()).thenReturn(Material.STONE_PRESSURE_PLATE); when(block.getLocation()).thenReturn(plates[0]); when(admin.getTargetBlockExact(6)).thenReturn(block);
        new GameCommands(plugin).admin(admin, new String[]{"unlink-checkpoint", arena.id, "one"});
        assertNull(arena.checkpointAt(plates[0])); assertTrue(arena.checkpoints.containsKey("one"));
        engine.join(admin, arena, false); engine.checkpoint(admin, "one"); assertEquals("one", engine.session(admin).checkpoint);
    }

    @Test void playersKeepIndependentPlateSelections() {
        Location[] plates = linkedPlates(); Player first = player(), second = player(); engine.join(first, arena, false); engine.join(second, arena, false);
        engine.checkpoint(first, "one", plates[0]); engine.checkpoint(second, "one", plates[1]);
        assertEquals(plates[0], engine.spawn(engine.session(first))); assertEquals(plates[1], engine.spawn(engine.session(second)));
    }
    @Test void linkedHeavyPlateIsCheckpointAndSeparateHeavyPlateFinishes() {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false);
        var block = mock(org.bukkit.block.Block.class); when(block.getType()).thenReturn(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        var event = mock(org.bukkit.event.player.PlayerMoveEvent.class); when(event.getPlayer()).thenReturn(p);
        when(event.getFrom()).thenReturn(new Location(world, 3, 65, 2)); when(event.getTo()).thenReturn(plates[1]);
        GameListener listener = new GameListener(plugin); listener.move(event);
        assertNotNull(engine.session(p)); assertEquals(plates[1], engine.spawn(engine.session(p))); assertEquals(0, store.stat(p.getUniqueId(), arena.id, "wins"));
        when(event.getTo()).thenReturn(new Location(world, 10.5, 65.1, 10.5)); listener.move(event);
        assertNull(engine.session(p)); assertEquals(1, store.stat(p.getUniqueId(), arena.id, "wins"));
    }

    @Test void unlimitedSoloDoesNotExpireButStillFinishes() {
        arena.duration = 0; Player p = player(); engine.join(p, arena, false);
        engine.session(p).previousElapsed = 100000000; engine.tick(); assertNotNull(engine.session(p));
        engine.finish(p); assertNull(engine.session(p)); assertEquals(1, store.stat(p.getUniqueId(), arena.id, "wins"));
    }
    @Test void unlimitedMultiplayerStillEndsOnWinningScore() {
        arena.mode = Arena.Mode.KILLS; arena.minPlayers = 2; arena.lobby = arena.start; arena.duration = 0; arena.target = 1;
        Player a = player(), b = player(); engine.join(a, arena, false); engine.join(b, arena, false); Match m = engine.match(arena); engine.start(m);
        engine.tick(); engine.tick(); assertEquals(Match.Phase.RUNNING, m.phase); assertEquals(0, m.remaining);
        engine.died(b, a); assertNull(engine.session(a)); assertEquals(1, store.stat(a.getUniqueId(), arena.id, "wins"));
    }
    @Test void positiveTimeLimitsStillExpire() {
        arena.duration = 1; Player p = player(); engine.join(p, arena, false); engine.session(p).previousElapsed = 2000;
        engine.tick(); assertNull(engine.session(p)); assertEquals(0, store.stat(p.getUniqueId(), arena.id, "wins"));
    }
    @Test void durationZeroCanBeSavedAndPositiveLimitRestored() throws Exception {
        GameCommands commands = new GameCommands(plugin); commands.option(arena, "duration", "0");
        assertTrue(arena.problems().isEmpty()); assertEquals("No time limit", arena.timeLimitLabel()); store.saveArenas();
        Store loaded = new Store(plugin); loaded.load(); assertEquals(0, loaded.arenas.get(arena.id).duration);
        commands.option(arena, "duration", "600"); assertTrue(arena.timed()); assertEquals("600s time limit", arena.timeLimitLabel());
        assertThrows(IllegalArgumentException.class, () -> commands.option(arena, "duration", "-1"));
    }
    @Test void unlimitedJuggernautEffectHasNoExpiry() {
        arena.duration = 0; assertEquals(org.bukkit.potion.PotionEffect.INFINITE_DURATION, GameEngine.effectDuration(new Match(arena)));
        arena.duration = 60; Match m = new Match(arena); m.remaining = 45; assertEquals(900, GameEngine.effectDuration(m));
    }

    @Test void manualCheckpointCanAdvanceOrRewindWithoutResettingTimer() throws Exception {
        Location[] plates = linkedPlates(); arena.checkpoints.put("two", new Location(world, 10, 65, 10)); Player p = player(); engine.join(p, arena, false);
        long started = engine.session(p).started;
        engine.assignCheckpoint(p.getUniqueId(), arena, "two", null); assertEquals("two", engine.session(p).checkpoint);
        engine.assignCheckpoint(p.getUniqueId(), arena, "one", plates[1]); assertEquals(plates[1], engine.spawn(engine.session(p))); assertEquals(started, engine.session(p).started);
    }
    @Test void manualOfflineSoloCheckpointIsSavedAndUsedOnJoin() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.assignCheckpoint(p.getUniqueId(), arena, "one", plates[1]);
        Store loaded = new Store(plugin); loaded.load(); assertEquals("one", loaded.checkpoint(p.getUniqueId(), arena.id)); assertEquals(plates[1], loaded.checkpointSpawn(p.getUniqueId(), arena.id));
        engine.join(p, arena, false); assertEquals(plates[1], engine.spawn(engine.session(p)));
    }
    @Test void manualCheckpointWriteFailureKeepsActiveState() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false); engine.checkpoint(p, "one", plates[0]);
        Files.delete(dir.resolve("progress.yml")); Files.createDirectory(dir.resolve("progress.yml"));
        assertThrows(java.io.IOException.class, () -> engine.assignCheckpoint(p.getUniqueId(), arena, "one", plates[1])); assertEquals(plates[0], engine.spawn(engine.session(p)));
    }
    @Test void manualCheckpointRejectsUnknownStagesAndInactiveRaces() {
        linkedPlates(); Player p = player();
        assertThrows(IllegalArgumentException.class, () -> engine.assignCheckpoint(p.getUniqueId(), arena, "missing", null));
        arena.mode = Arena.Mode.RACE;
        assertThrows(IllegalArgumentException.class, () -> engine.assignCheckpoint(p.getUniqueId(), arena, "one", null));
    }
    @Test void checkpointAdminCommandSupportsConsoleAndOptionalPlateTeleport() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false);
        bukkit.when(() -> Bukkit.getPlayerExact("target")).thenReturn(p);
        var sender = mock(org.bukkit.command.ConsoleCommandSender.class); when(sender.hasPermission("lumigames.admin")).thenReturn(true);
        clearInvocations(p);
        new GameCommands(plugin).onCommand(sender, null, "lg", new String[]{"admin", "set-checkpoint", arena.id, "target", "one", "2", "teleport"});
        assertEquals(plates[1], engine.spawn(engine.session(p))); verify(p).teleport(plates[1], TeleportCause.PLUGIN);
    }
    @Test void assignmentDoesNotTeleportUnlessRequested() throws Exception {
        linkedPlates(); Player p = player(); engine.join(p, arena, false); bukkit.when(() -> Bukkit.getPlayerExact("target")).thenReturn(p);
        var sender = mock(org.bukkit.command.ConsoleCommandSender.class); when(sender.hasPermission("lumigames.admin")).thenReturn(true); clearInvocations(p);
        new GameCommands(plugin).setPlayerCheckpoint(sender, new String[]{arena.id, "target", "one"});
        assertEquals("one", engine.session(p).checkpoint); verify(p, never()).teleport(any(Location.class), any(TeleportCause.class));
    }
    @Test void checkpointCommandRejectsNonAdminsAndInvalidPlateBeforeChangingProgress() throws Exception {
        linkedPlates(); Player p = player(); bukkit.when(() -> Bukkit.getPlayerExact("target")).thenReturn(p);
        var sender = mock(org.bukkit.command.ConsoleCommandSender.class); GameCommands commands = new GameCommands(plugin);
        assertThrows(IllegalArgumentException.class, () -> commands.setPlayerCheckpoint(sender, new String[]{arena.id, "target", "one"}));
        when(sender.hasPermission("lumigames.admin")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> commands.setPlayerCheckpoint(sender, new String[]{arena.id, "target", "one", "3"})); assertNull(store.checkpoint(p.getUniqueId(), arena.id));
    }
    @Test void blockedManualTeleportKeepsAssignedCheckpointAndRecovery() throws Exception {
        Location[] plates = linkedPlates(); Player p = player(); engine.join(p, arena, false); bukkit.when(() -> Bukkit.getPlayerExact("target")).thenReturn(p);
        when(p.teleport(any(Location.class), eq(TeleportCause.PLUGIN))).thenReturn(false);
        var sender = mock(org.bukkit.command.ConsoleCommandSender.class); when(sender.hasPermission("lumigames.admin")).thenReturn(true);
        new GameCommands(plugin).setPlayerCheckpoint(sender, new String[]{arena.id, "target", "one", "2", "teleport"});
        assertEquals(plates[1], engine.spawn(engine.session(p))); assertTrue(engine.recovery(p.getUniqueId()).exists()); assertFalse(engine.session(p).restoring);
        verify(sender).sendMessage("Checkpoint saved, but the teleport was blocked. The player can retry with /lg revert.");
    }
    @Test void explicitOfflineUuidCanBeAssignedAndCannotBeTeleported() throws Exception {
        linkedPlates(); UUID offline = UUID.randomUUID(); var sender = mock(org.bukkit.command.ConsoleCommandSender.class); when(sender.hasPermission("lumigames.admin")).thenReturn(true);
        GameCommands commands = new GameCommands(plugin);
        assertThrows(IllegalArgumentException.class, () -> commands.setPlayerCheckpoint(sender, new String[]{arena.id, offline.toString(), "one", "teleport"})); assertNull(store.checkpoint(offline, arena.id));
        commands.setPlayerCheckpoint(sender, new String[]{arena.id, offline.toString(), "one"}); assertEquals("one", store.checkpoint(offline, arena.id));
    }

}
