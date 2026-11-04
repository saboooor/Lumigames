package ca.saboor.lumigames;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class GameListener implements Listener {
    static final NamespacedKey PROJECTILE_ARENA = new NamespacedKey("lumigames", "arena");
    final Lumigames plugin;
    final GameEngine engine;
    GameListener(Lumigames plugin) { this.plugin = plugin; engine = plugin.engine; }
    Arena arena(Location l) { return plugin.store.arenas.values().stream().filter(a -> a.enabled && a.inside(l)).findFirst().orElse(null); }
    boolean sameBlock(Location a, Location b) {
        return a != null && b != null && Objects.equals(a.getWorld(), b.getWorld()) && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
    boolean near(Location a, Location b) { return a != null && b != null && Objects.equals(a.getWorld(), b.getWorld()) && a.distanceSquared(b) < 3; }
    @EventHandler public void join(PlayerJoinEvent e) { plugin.sync(() -> engine.recover(e.getPlayer())); }
    @EventHandler public void quit(PlayerQuitEvent e) { plugin.menus.views.remove(e.getPlayer().getUniqueId()); engine.disconnect(e.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (engine.internalTeleport.contains(e.getPlayer().getUniqueId())) return;
        Session s = engine.session(e.getPlayer());
        if (s != null) { e.setCancelled(true); plugin.sync(() -> engine.leave(e.getPlayer())); }
        else if (arena(e.getTo()) != null && !e.getPlayer().hasPermission("lumigames.admin")) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getX() == e.getTo().getX() && e.getFrom().getY() == e.getTo().getY() && e.getFrom().getZ() == e.getTo().getZ()) return;
        Player p = e.getPlayer(); Session s = engine.session(p);
        if (s == null) {
            if (engine.pending(p)) { e.setTo(e.getFrom()); return; }
            Arena a = arena(e.getTo()); if (a != null && !a.inside(e.getFrom()) && !p.hasPermission("lumigames.admin")) e.setTo(e.getFrom());
            return;
        }
        if (s.restoring) { e.setTo(e.getFrom()); return; }
        if (!s.arena.inside(e.getTo())) {
            if (engine.active(s) && (s.arena.mode == Arena.Mode.SPLEEF || s.arena.mode == Arena.Mode.LIVES)) {
                e.setTo(e.getFrom()); p.setHealth(0);
            } else { Location target = s.spectator ? (s.arena.spectator == null ? s.arena.start : s.arena.spectator) : engine.spawn(s); e.setTo(target); p.setFallDistance(0); }
            return;
        }
        if (!engine.active(s)) return;
        Material feet = e.getTo().getBlock().getType();
        Material checkpoint = Material.matchMaterial(plugin.getConfig().getString("checkpoint-material", "LIGHT_WEIGHTED_PRESSURE_PLATE"));
        Material finish = Material.matchMaterial(plugin.getConfig().getString("finish-material", "HEAVY_WEIGHTED_PRESSURE_PLATE"));
        Arena.CheckpointPlate linked = Arena.pressurePlate(feet) ? s.arena.checkpointAt(e.getTo()) : null;
        if (linked != null && !s.arena.checkpointPlates.containsKey(linked.id()) && feet != checkpoint) linked = null;
        if (linked != null) engine.checkpoint(p, linked.id(), linked.spawn());
        if (linked == null && feet == finish) engine.finish(p);
        if (engine.active(s) && s.arena.mode == Arena.Mode.CTF) flag(p, s, e.getTo());
    }
    void flag(Player p, Session s, Location location) {
        Location own = s.team.equals("red") ? s.arena.redFlag : s.arena.blueFlag;
        Location enemy = s.team.equals("red") ? s.arena.blueFlag : s.arena.redFlag;
        Match m = engine.match(s.arena);
        if (!s.carryingFlag && near(location, enemy)) {
            boolean taken = engine.players(m).stream().anyMatch(other -> other.team.equals(s.team) && other.carryingFlag);
            if (!taken) { s.carryingFlag = true; plugin.message(p, "Enemy flag captured! Return to your flag."); }
        } else if (s.carryingFlag && near(location, own)) {
            boolean ownStolen = engine.players(m).stream().anyMatch(other -> !other.team.equals(s.team) && other.carryingFlag);
            if (!ownStolen) { s.carryingFlag = false; engine.score(s, 1); plugin.message(p, "Flag delivered!"); }
        }
    }
    Player attacker(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        if (entity instanceof Tameable pet && pet.getOwner() instanceof Player p) return p;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session victim = engine.session(p);
        Player killer = e instanceof EntityDamageByEntityEvent by ? attacker(by.getDamager()) : null;
        Session attacker = killer == null ? null : engine.session(killer);
        if (e instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Projectile projectile) {
            String origin = projectile.getPersistentDataContainer().get(PROJECTILE_ARENA, org.bukkit.persistence.PersistentDataType.STRING);
            if (origin != null && (victim == null || !victim.arena.id.equals(origin))) { e.setCancelled(true); return; }
        }
        if (victim == null) { if (attacker != null || engine.pending(p)) e.setCancelled(true); return; }
        if (!engine.active(victim)) { e.setCancelled(true); return; }
        if (e instanceof EntityDamageByEntityEvent) {
            if (!engine.active(attacker) || attacker.arena != victim.arena || !victim.arena.pvp || victim.arena.teams && victim.team.equals(attacker.team)) { e.setCancelled(true); return; }
            if (victim.arena.paintball && e instanceof EntityDamageByEntityEvent hit && (hit.getDamager() instanceof Snowball || hit.getDamager() instanceof Egg)) e.setDamage(1000);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(PlayerDeathEvent e) {
        Player p = e.getEntity(); if (!engine.pending(p)) return;
        e.getDrops().clear(); e.setDroppedExp(0); e.setKeepInventory(true); e.setKeepLevel(true);
        engine.died(p, p.getKiller());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent e) {
        Session s = engine.session(e.getPlayer());
        if (s != null) {
            Location l = s.restoring ? s.returnLocation : s.spectator ? (s.arena.spectator == null ? s.arena.start : s.arena.spectator) : engine.spawn(s);
            if (l != null && l.getWorld() != null) e.setRespawnLocation(l);
        }
        plugin.sync(() -> {
            Player p = e.getPlayer(); Session current = engine.session(p);
            if (current == null || current.restoring) engine.recover(p);
            else engine.prepare(p, current, current.spectator);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        if (!engine.pending(e.getPlayer())) return;
        String first = e.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String command = first.contains(":") ? first.substring(first.indexOf(':') + 1) : first;
        if (Set.of("lg", "lumigames", "minigame", "mg").contains(command) || plugin.getConfig().getStringList("allowed-commands").contains(command)) return;
        e.setCancelled(true); plugin.error(e.getPlayer(), "Use /lg leave before using other commands.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        Arena a = arena(e.getBlock().getLocation()); Session s = engine.session(e.getPlayer());
        if (s == null && a == null) return;
        if (s == null && e.getPlayer().hasPermission("lumigames.admin") && !engine.matches.containsKey(a.id)) return;
        if (!engine.active(s) || s.arena != a || !a.breakBlocks || e.getBlock().getState() instanceof TileState
            || a.mode == Arena.Mode.SPLEEF && e.getBlock().getType() != Material.SNOW_BLOCK
            || !engine.record(e.getBlock(), a)) { e.setCancelled(true); return; }
        e.setDropItems(false); e.setExpToDrop(0);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Arena a = arena(e.getBlock().getLocation()); Session s = engine.session(e.getPlayer());
        if (s == null && a == null) return;
        if (s == null && e.getPlayer().hasPermission("lumigames.admin") && !engine.matches.containsKey(a.id)) return;
        // Restrict placed blocks to simple solid blocks; no fluids, containers, gravity or redstone physics.
        Material t = e.getBlock().getType();
        if (e instanceof BlockMultiPlaceEvent || !engine.active(s) || s.arena != a || !a.placeBlocks || !Set.of(Material.STONE, Material.COBBLESTONE, Material.DIRT, Material.OAK_PLANKS, Material.WHITE_WOOL).contains(t)) { e.setCancelled(true); return; }
        // BlockPlaceEvent sees the placed block; log the replaced state instead.
        BlockState old = e.getBlockReplacedState();
        if (!plugin.recordReplacement(e.getBlock(), old, a)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL && e.getClickedBlock() != null) {
            Session session = engine.session(e.getPlayer()); Block plate = e.getClickedBlock();
            if (engine.active(session) && Arena.pressurePlate(plate.getType())) {
                Arena.CheckpointPlate link = session.arena.checkpointAt(plate.getLocation());
                Material configured = Material.matchMaterial(plugin.getConfig().getString("checkpoint-material", "LIGHT_WEIGHTED_PRESSURE_PLATE"));
                if (link != null && (session.arena.checkpointPlates.containsKey(link.id()) || plate.getType() == configured)) engine.checkpoint(e.getPlayer(), link.id(), link.spawn());
            }
        }
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer(); Session s = engine.session(p); Block block = e.getClickedBlock();
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL && block != null && block.getType() == Material.FARMLAND && (engine.pending(p) || arena(block.getLocation()) != null)) { e.setCancelled(true); return; }
        if (block != null && block.getState() instanceof Sign sign && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            String marker = plain(sign.line(0));
            if (marker.equalsIgnoreCase("[Lumigames]") || marker.equalsIgnoreCase("[Minigame]")) {
                e.setCancelled(true); useSign(p, sign); return;
            }
        }
        if (s != null || engine.pending(p)) {
            if (block != null && engine.active(s) && s.arena.mode == Arena.Mode.TREASURE && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                for (int i = 0; i < s.arena.treasures.size(); i++) if (sameBlock(block.getLocation(), s.arena.treasures.get(i)) && s.treasures.add(i)) {
                    plugin.message(p, "Treasure found! " + s.treasures.size() + "/" + Math.min(s.arena.target, s.arena.treasures.size()));
                    if (s.treasures.size() >= Math.min(s.arena.target, s.arena.treasures.size())) engine.win(s);
                }
            }
            if (block != null && (block.getState() instanceof TileState || block.getType().isInteractable()) && !block.getType().name().endsWith("PRESSURE_PLATE")) e.setCancelled(true);
            if (e.getItem() != null && !e.getItem().getType().isBlock() && e.getItem().getType() != Material.BOW && e.getItem().getType() != Material.CROSSBOW && !e.getItem().getType().isEdible()) e.setUseItemInHand(Event.Result.DENY);
        } else if (block != null && arena(block.getLocation()) != null && !p.hasPermission("lumigames.admin")) e.setCancelled(true);
    }
    String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }
    void useSign(Player p, Sign sign) {
        String action = plain(sign.line(1)).toLowerCase(Locale.ROOT), id = plain(sign.line(2));
        Arena a = plugin.store.arenas.get(id); Session s = engine.session(p);
        if (action.equals("leave") || action.equals("quit")) { engine.leave(p); return; }
        if (a == null) { plugin.error(p, "Unknown arena on this sign."); return; }
        if (action.equals("join")) { engine.join(p, a, false); return; }
        if (action.equals("spectate")) { engine.join(p, a, true); return; }
        if (s == null || s.arena != a) return;
        switch (action) {
            case "checkpoint" -> engine.checkpoint(p, plain(sign.line(3)));
            case "finish" -> engine.finish(p);
            case "revert" -> engine.revert(p);
            case "loadout" -> { if (!engine.running(s)) engine.prepare(p, s, s.spectator); }
            case "team" -> plugin.commands.team(p, plain(sign.line(3)));
            case "score" -> { if (a.mode == Arena.Mode.CUSTOM && engine.active(s) && s.treasures.add(sign.getLocation().hashCode())) engine.score(s, 1); }
            default -> plugin.menus.detail(p, a);
        }
    }
    @EventHandler(ignoreCancelled = true) public void sign(SignChangeEvent e) {
        String first = plain(Objects.requireNonNullElse(e.line(0), Component.empty()));
        if ((first.equalsIgnoreCase("[Lumigames]") || first.equalsIgnoreCase("[Minigame]")) && !e.getPlayer().hasPermission("lumigames.admin")) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void drop(PlayerDropItemEvent e) { if (engine.pending(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && engine.pending(p)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void hunger(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && engine.pending(p)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void inventory(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && engine.pending(p) && e.getInventory().getType() != InventoryType.CRAFTING) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void click(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && engine.pending(p) && (e.getSlotType() == InventoryType.SlotType.CRAFTING || e.getSlotType() == InventoryType.SlotType.RESULT)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && engine.pending(p)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void bucket(PlayerBucketEmptyEvent e) { if (engine.pending(e.getPlayer()) || arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void bucketFill(PlayerBucketFillEvent e) { if (engine.pending(e.getPlayer()) || arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void portal(PlayerPortalEvent e) { if (engine.pending(e.getPlayer())) { e.setCancelled(true); plugin.sync(() -> engine.leave(e.getPlayer())); } }
    @EventHandler(ignoreCancelled = true) public void entityInteract(PlayerInteractEntityEvent e) { if (engine.pending(e.getPlayer()) || arena(e.getRightClicked().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void armorStand(PlayerArmorStandManipulateEvent e) { if (engine.pending(e.getPlayer()) || arena(e.getRightClicked().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void entityDamage(EntityDamageByEntityEvent e) { Player p = attacker(e.getDamager()); if (!(e.getEntity() instanceof Player) && (arena(e.getEntity().getLocation()) != null || p != null && engine.pending(p))) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void projectile(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p) || !engine.pending(p)) return;
        Session s = engine.session(p);
        if (!engine.active(s)) { e.setCancelled(true); return; }
        e.getEntity().getPersistentDataContainer().set(PROJECTILE_ARENA, org.bukkit.persistence.PersistentDataType.STRING, s.arena.id);
        if (s.arena.infiniteAmmo && (e.getEntity() instanceof Snowball || e.getEntity() instanceof Egg)) {
            Material type = e.getEntity() instanceof Snowball ? Material.SNOWBALL : Material.EGG;
            plugin.sync(() -> { if (engine.session(p) == s && engine.active(s)) p.getInventory().addItem(new org.bukkit.inventory.ItemStack(type)); });
        }
    }
    @EventHandler(ignoreCancelled = true) public void splash(PotionSplashEvent e) { for (LivingEntity entity : e.getAffectedEntities()) if (entity instanceof Player p && engine.pending(p)) e.setIntensity(entity, 0); }
    @EventHandler(ignoreCancelled = true) public void cloud(AreaEffectCloudApplyEvent e) { e.getAffectedEntities().removeIf(entity -> entity instanceof Player p && engine.pending(p)); }
    @EventHandler(ignoreCancelled = true) public void bow(EntityShootBowEvent e) { if (e.getEntity() instanceof Player p) { Session s = engine.session(p); if (engine.active(s) && s.arena.infiniteAmmo) e.setConsumeItem(false); } }
    @EventHandler(ignoreCancelled = true) public void explode(EntityExplodeEvent e) { e.blockList().removeIf(b -> arena(b.getLocation()) != null); }
    @EventHandler(ignoreCancelled = true) public void blockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> arena(b.getLocation()) != null); }
    @EventHandler(ignoreCancelled = true) public void fluid(BlockFromToEvent e) { if (arena(e.getBlock().getLocation()) != null || arena(e.getToBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void spread(BlockSpreadEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void grow(BlockGrowEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void form(BlockFormEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void fade(BlockFadeEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void structure(org.bukkit.event.world.StructureGrowEvent e) { if (e.getBlocks().stream().anyMatch(b -> arena(b.getLocation()) != null)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void fertilize(BlockFertilizeEvent e) { if (e.getBlocks().stream().anyMatch(b -> arena(b.getLocation()) != null)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void harvest(PlayerHarvestBlockEvent e) { if (engine.pending(e.getPlayer()) || arena(e.getHarvestedBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void entityPhysical(EntityInteractEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void burn(BlockBurnEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void ignite(BlockIgniteEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void physics(BlockPhysicsEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void entityBlock(EntityChangeBlockEvent e) { if (arena(e.getBlock().getLocation()) != null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void piston(BlockPistonExtendEvent e) { if (arena(e.getBlock().getLocation()) != null || e.getBlocks().stream().anyMatch(b -> arena(b.getLocation()) != null || arena(b.getRelative(e.getDirection()).getLocation()) != null)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void retract(BlockPistonRetractEvent e) { if (arena(e.getBlock().getLocation()) != null || e.getBlocks().stream().anyMatch(b -> arena(b.getLocation()) != null || arena(b.getRelative(e.getDirection()).getLocation()) != null)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void portalCreate(PortalCreateEvent e) { if (e.getBlocks().stream().anyMatch(b -> arena(b.getLocation()) != null)) e.setCancelled(true); }
}
