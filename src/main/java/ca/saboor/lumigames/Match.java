package ca.saboor.lumigames;

import java.util.*;

final class Match {
    enum Phase { WAITING, COUNTDOWN, RUNNING }
    final Arena arena;
    final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    Phase phase = Phase.WAITING;
    int remaining, redScore, blueScore;
    UUID juggernaut;
    Match(Arena arena) { this.arena = arena; }
}
