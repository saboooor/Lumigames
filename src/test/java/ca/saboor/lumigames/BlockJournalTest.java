package ca.saboor.lumigames;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockJournalTest {
    @TempDir Path dir;
    @Test void firstBlockStateSurvivesDuplicateChangesAndRestart() throws Exception {
        UUID id = UUID.randomUUID(); World world = mock(World.class); when(world.getUID()).thenReturn(id);
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(1); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(2);
        when(world.getBlockAt(1, 64, 2)).thenReturn(block);
        BlockData data = mock(BlockData.class); when(data.getAsString()).thenReturn("minecraft:snow_block"); when(block.getBlockData()).thenReturn(data);
        Path file = dir.resolve("rollback.log"); BlockJournal journal = new BlockJournal(file.toFile());
        assertTrue(journal.record(block, 2));
        when(data.getAsString()).thenReturn("minecraft:air"); assertTrue(journal.record(block, 2));
        assertEquals(1, Files.readAllLines(file).size()); assertTrue(Files.readString(file).contains("minecraft:snow_block"));
        BlockJournal restart = new BlockJournal(file.toFile()); restart.load();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:snow_block")).thenReturn(data);
            assertTrue(restart.restore()); verify(block).setBlockData(data, false);
        }
        assertFalse(Files.exists(file)); assertFalse(restart.pending());
    }
    @Test void unavailableWorldKeepsRecoveryLog() throws Exception {
        Path file = dir.resolve("rollback.log"); Files.writeString(file, UUID.randomUUID() + " 1 64 2 minecraft:stone\n");
        BlockJournal journal = new BlockJournal(file.toFile()); journal.load();
        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) { assertFalse(journal.restore()); }
        assertTrue(Files.exists(file)); assertTrue(journal.pending());
    }
    @Test void limitPreventsUnrecordedChanges() throws Exception {
        World w = mock(World.class); when(w.getUID()).thenReturn(UUID.randomUUID());
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(w);
        BlockData data = mock(BlockData.class); when(block.getBlockData()).thenReturn(data); when(data.getAsString()).thenReturn("minecraft:stone");
        BlockJournal journal = new BlockJournal(dir.resolve("rollback.log").toFile());
        assertTrue(journal.record(block, 1)); when(block.getX()).thenReturn(2); assertFalse(journal.record(block, 1));
    }
    @Test void corruptJournalIsPreserved() throws Exception {
        Path file = dir.resolve("rollback.log"); Files.writeString(file, "corrupt data");
        assertThrows(java.io.IOException.class, () -> new BlockJournal(file.toFile()).load()); assertEquals("corrupt data", Files.readString(file));
    }
}
