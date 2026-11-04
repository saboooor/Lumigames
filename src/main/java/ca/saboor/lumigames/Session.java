package ca.saboor.lumigames;

import java.util.*;
import org.bukkit.Location;

final class Session {
    final UUID player;
    final Arena arena;
    final PlayerSnapshot snapshot;
    long started = System.currentTimeMillis();
    long previousElapsed;
    int lives, score;
    String team = "", checkpoint, kit = "default";
    boolean spectator, eliminated, restoring, carryingFlag, pendingWin;
    long finishTime;
    Location returnLocation, checkpointSpawn;
    final Set<Integer> treasures = new HashSet<>();
    Session(UUID player, Arena arena, PlayerSnapshot snapshot) {
        this.player = player; this.arena = arena; this.snapshot = snapshot; lives = arena.lives;
        returnLocation = arena.exit == null ? snapshot.location().clone() : arena.exit.clone();
    }
    long elapsed() { return previousElapsed + System.currentTimeMillis() - started; }
}
