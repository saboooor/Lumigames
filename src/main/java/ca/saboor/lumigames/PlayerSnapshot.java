package ca.saboor.lumigames;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** Full survival state, independent of the temporary match inventory. */
record PlayerSnapshot(Location location, ItemStack[] contents, GameMode mode, double health,
    int food, float saturation, float exhaustion, int level, float exp, int totalExp,
    boolean flight, boolean flying, float flySpeed, float walkSpeed, int fire, int air,
    float fall, double absorption, Collection<PotionEffect> effects) {
    static PlayerSnapshot capture(Player p) {
        return new PlayerSnapshot(p.getLocation().clone(), cloneItems(p.getInventory().getContents()), p.getGameMode(), p.getHealth(),
            p.getFoodLevel(), p.getSaturation(), p.getExhaustion(), p.getLevel(), p.getExp(), p.getTotalExperience(),
            p.getAllowFlight(), p.isFlying(), p.getFlySpeed(), p.getWalkSpeed(), p.getFireTicks(), p.getRemainingAir(),
            p.getFallDistance(), p.getAbsorptionAmount(), List.copyOf(p.getActivePotionEffects()));
    }
    static ItemStack[] cloneItems(ItemStack[] items) {
        return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
    }
    void restore(Player p) {
        p.closeInventory(); p.setGameMode(mode); p.getInventory().setContents(cloneItems(contents));
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType())); p.addPotionEffects(effects);
        p.setHealth(Math.max(0.5, Math.min(health, Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue())));
        p.setFoodLevel(food); p.setSaturation(saturation); p.setExhaustion(exhaustion);
        p.setTotalExperience(totalExp); p.setLevel(level); p.setExp(exp);
        p.setAllowFlight(flight); p.setFlying(flight && flying); p.setFlySpeed(flySpeed); p.setWalkSpeed(walkSpeed);
        p.setFireTicks(fire); p.setRemainingAir(air); p.setFallDistance(fall); p.setAbsorptionAmount(absorption);
    }
    void save(ConfigurationSection c) {
        c.set("location", location); c.set("inventory", Arrays.asList(contents)); c.set("mode", mode.name());
        c.set("health", health); c.set("food", food); c.set("saturation", saturation); c.set("exhaustion", exhaustion);
        c.set("level", level); c.set("exp", exp); c.set("total-exp", totalExp); c.set("flight", flight); c.set("flying", flying);
        c.set("fly-speed", flySpeed); c.set("walk-speed", walkSpeed); c.set("fire", fire); c.set("air", air);
        c.set("fall", fall); c.set("absorption", absorption); c.set("effects", new ArrayList<>(effects));
    }
    static PlayerSnapshot load(ConfigurationSection c) {
        if (!c.isList("inventory") || !c.isString("mode") || !c.isSet("health")) throw new IllegalArgumentException("Incomplete player snapshot; original file preserved.");
        List<?> values = c.getList("inventory", List.of());
        if (values.size() != 41) throw new IllegalArgumentException("Recovery inventory must contain all 41 slots.");
        for (Object value : values) if (value != null && !(value instanceof ItemStack)) throw new IllegalArgumentException("Unreadable recovery item; original file preserved.");
        ItemStack[] items = new ItemStack[41];
        for (int i = 0; i < Math.min(items.length, values.size()); i++) if (values.get(i) instanceof ItemStack item) items[i] = item;
        List<PotionEffect> effects = new ArrayList<>();
        for (Object value : c.getList("effects", List.of())) if (value instanceof PotionEffect e) effects.add(e);
        Location l = c.getLocation("location");
        if (l == null) throw new IllegalArgumentException("Recovery location is missing.");
        return new PlayerSnapshot(l, items, GameMode.valueOf(c.getString("mode", "SURVIVAL")), c.getDouble("health", 20),
            c.getInt("food", 20), (float)c.getDouble("saturation", 5), (float)c.getDouble("exhaustion"),
            c.getInt("level"), (float)c.getDouble("exp"), c.getInt("total-exp"), c.getBoolean("flight"), c.getBoolean("flying"),
            (float)c.getDouble("fly-speed", .1), (float)c.getDouble("walk-speed", .2), c.getInt("fire"), c.getInt("air", 300),
            (float)c.getDouble("fall"), c.getDouble("absorption"), effects);
    }
}
