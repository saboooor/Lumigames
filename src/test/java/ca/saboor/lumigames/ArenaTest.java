package ca.saboor.lumigames;

import org.junit.jupiter.api.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArenaTest {
    World world = mock(World.class);
    Arena arena() {
        Arena a = new Arena("test", Arena.Mode.SOLO);
        a.corner1 = new Location(world, -10, 0, -10); a.corner2 = new Location(world, 10, 100, 10); a.start = new Location(world, 0, 64, 0);
        return a;
    }
    @Test void boundsSupportReverseCornersAndNegativeCoordinates() {
        Arena a = arena(); Location original = a.corner1; a.corner1 = a.corner2; a.corner2 = original;
        assertTrue(a.inside(new Location(world, -9.5, 1, -9.5))); assertTrue(a.inside(new Location(world, 10.9, 64, 10.9)));
        assertFalse(a.inside(new Location(world, 11, 64, 0))); assertFalse(a.inside(new Location(mock(World.class), 0, 64, 0)));
    }
    @Test void invalidSetupIsExplained() {
        Arena a = new Arena("new", Arena.Mode.CTF); assertFalse(a.problems().isEmpty());
        a = arena(); assertTrue(a.problems().isEmpty()); a.maxPlayers = 0; assertFalse(a.problems().isEmpty());
    }
    @Test void allModesRoundTripRulesAndCheckpointOrder() {
        for (Arena.Mode mode : Arena.Mode.values()) {
            Arena a = arena(); a.mode = mode; a.checkpoints.put("z", a.start); a.checkpoints.put("a", a.start); a.rewards.add("give {player} diamond 1");
            YamlConfiguration yaml = new YamlConfiguration(); a.save(yaml);
            Arena loaded = Arena.load("test", yaml);
            assertEquals(mode, loaded.mode); assertEquals(List.of("z", "a"), new ArrayList<>(loaded.checkpoints.keySet())); assertEquals(a.rewards, loaded.rewards);
        }
    }
    @Test void arenaIdsCannotInjectYamlPathsOrCommands() {
        for (String invalid : List.of("../test", "a.b", "a b", "A", "", "a\nstop")) assertThrows(IllegalArgumentException.class, () -> new Arena(invalid, Arena.Mode.SOLO));
    }
    @Test void numericAndBooleanRulesRejectInvalidInput() {
        assertEquals(5, GameCommands.integer("5", 1, 10));
        for (String invalid : List.of("0", "11", "NaN", "2147483648")) assertThrows(IllegalArgumentException.class, () -> GameCommands.integer(invalid, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> GameCommands.bool("yes")); assertTrue(GameCommands.bool("true"));
    }
    @Test void linkedPlateDefinitionsRoundTripAndLegacyCheckpointsStillActivate() {
        Arena a = arena(); a.checkpoints.put("one", a.start);
        assertNotNull(a.checkpointAt(a.start));
        Location other = new Location(world, 3.5, 65.1, 3.5);
        a.checkpointPlates.put("one", List.of(a.start, other));
        YamlConfiguration yaml = new YamlConfiguration(); a.save(yaml); Arena loaded = Arena.load("test", yaml);
        assertEquals(List.of(a.start, other), loaded.checkpointPlates.get("one")); assertEquals(other, loaded.checkpointAt(other).spawn());
        a.checkpointPlates.put("one", List.of()); a.save(yaml); loaded = Arena.load("test", yaml); assertNull(loaded.checkpointAt(a.start));
    }

}
