# Lumigames

A new Java 21 / Paper 1.21.10+ minigames plugin, built alongside the supplied `Minigames/` reference. The reference source is unchanged. The root Gradle project builds **Lumigames**, with no dependency on the old plugin, Vault, ProtocolLib or a resource pack.

The UI follows LumiGuide and Lumistones: native Minecraft dialogs, vanilla item sprites, Adventure messages, paginated arena lists, tooltips, and short action-bar status updates. This release targets Java Edition clients with native dialog support. It is not a Folia plugin.

## Build and install

```sh
./gradlew clean test build
```

Install `build/libs/Lumigames-1.4.0.jar` in the Paper server's `plugins/` directory and restart. Use `/lg` to browse games and `/lg admin` to open management. Do not install the old Minigames plugin alongside it: both own `/mg` and `/minigame` aliases. Back up existing server data before switching plugins. Use the migration preview and import described below to bring across legacy arena configurations. Player databases are not imported.

No arena is enabled automatically. Administrators define boundaries, spawns, objectives and loadouts first. Definitions are written immediately to `plugins/Lumigames/arenas.yml`.

## Game modes

| Mode | Objective |
| --- | --- |
| `SOLO` | Complete an ordered checkpoint course. Saved progress and elapsed time resume on rejoin. |
| `RACE` | First player to finish after visiting every checkpoint wins. Each match starts fresh. |
| `KILLS` | Reach the target kill score; supports red/blue teams. |
| `LIVES` | Last player or team remaining wins. Configurable lives. |
| `SPLEEF` | Break snow blocks and eliminate opponents. Leaving the playable cuboid costs a life. Snow is restored after the match. |
| `CTF` | Take the enemy flag and return to your own flag while it is home. Death or leaving returns a carried flag. |
| `INFECTION` | One player starts infected. Death infects survivors; survivors win if time expires. |
| `JUGGERNAUT` | Only the Juggernaut earns kill points. Killing them transfers the role and resistance buff. |
| `TREASURE` | Right-click distinct configured treasure blocks. Find the target count, or all configured treasures if fewer. `/lg hint` gives distance to the nearest remaining treasure. |
| `CUSTOM` | Award points through admin commands or once-per-session score signs. Supports teams. |

Multiplayer games require at least two players. Countdown cancels when too few participants remain; spectators never count. Active elimination games resolve when opponents leave. Score ties and unfinished races time out without a winner. Infection awards surviving blue players when time expires.

## Migrate from Minigames

Keep the old plugin's data directory, remove its JAR, and start the server with Lumigames. The importer reads the old configuration files directly; the old plugin does not need to be running. Load all worlds referenced by those arenas first.

Use **Migrate Minigames** in `/lg admin`, or run these commands as an administrator (also supported from the server console):

```text
/lg admin migrate preview
/lg admin migrate apply
```

The default source is the sibling `plugins/Minigames` data folder. You can also specify another plugin data directory, its `minigames` subdirectory, an individual arena directory, or an arena YAML file:

```text
/lg admin migrate preview plugins/Minigames-backup
/lg admin migrate apply plugins/Minigames-backup
```

All arguments after `preview` or `apply` are treated as one path, so paths containing spaces do not need quotes. Relative paths are resolved from the server working directory.

Preview reads files and lists conversions, skips and warnings without writing anything. Apply reads the source again, backs up the exact existing `arenas.yml`, writes a detailed report under `plugins/Lumigames/migration/<run>/`, and adds the converted arenas. **All imported arenas start disabled. Existing IDs are skipped, never overwritten. Original Minigames files stay unchanged.** Repeating an import skips arenas already present.

Supported source layouts include `Minigames/minigames/<name>/config.yml`, older flat `minigames/<name>.yml` files, and individual YAML files with one or more named arena sections. The importer maps:

- Display names, objective text, supported game mechanics, player limits, score targets, lives and duration.
- Start, lobby, quit and spectator locations, with world, yaw and pitch; red/blue team start positions.
- New `regenRegions` cuboids and old `regenarea.1`/`regenarea.2` bounds. Multiple regions become one enclosing cuboid; review the gaps and height before enabling.
- Block-rule, flight, paintball, infinite-ammo, permission and supported late-join settings.
- Current `loadouts`, old `loadout` and `extraloadouts`, named kits, equipment, supported item metadata and potion effects. Legacy armor slots `100–103` and offhand `-106` convert to the new inventory slots.

The old lobby wait and start countdown are combined into one countdown. Unlimited or missing legacy timers remain unlimited (`duration 0`). Only the first spawn in a spawn list transfers. Uncapped solo capacity becomes 100. Random score ranges use the maximum target. Legacy permission requirements become `lumigames.arena.<new-id>`. IDs and kit names are normalized to the new format; collisions are reported and skipped.

Review the report, edit each arena using `/lg admin edit <id>`, then enable it with `/lg admin enable <id>`. Enabling still runs all setup and overlap checks. The importer does not guess finish/checkpoint signs, CTF flag locations, or fixed treasure objectives from world blocks. The old `endpos` is a post-win teleport location, so it is not treated as a finish trigger or quit location. Set these objectives manually.

An arena referencing an unloaded world or an unreadable item is skipped with a reason, preserving the source for a retry. Unknown mechanics become disabled CUSTOM arenas and require manual objectives. Unsupported rules, custom loadout options, extra team colors and separate module files are called out in the report. Rewards, economy/betting commands and scripts are not imported or executed; recreate winner commands after reviewing them. Global server settings (command policy, database credentials and inventory policy), world signs, player checkpoints/recovery and SQL statistics are not migrated.

To undo an import, stop the server and restore `arenas.before.yml` from that run's migration folder over `plugins/Lumigames/arenas.yml`. This restores the complete arena file as it was immediately before the import, so back up any later edits first.

## First course

Stand at the two opposite corners of a cuboid containing the course, including its full height:

```text
/lg admin create parkour solo
/lg admin set parkour corner1
/lg admin set parkour corner2
/lg admin set parkour start
```

Move between commands. Set the start inside the cuboid. Stand on each **light weighted pressure plate** in course order and save its position:

```text
/lg admin checkpoint parkour first
/lg admin checkpoint parkour second
```

You can link **any pressure plate type** directly instead of using checkpoint signs. Look at a plate within six blocks (or stand on it) and run:

```text
/lg admin link-checkpoint parkour first
```

Look at a different plate and run the **same command with the same checkpoint ID** to add another plate for that stage. Linking creates the checkpoint if it does not exist. Add new checkpoint IDs in course order. Stop the active game before editing links.

Each player respawns at the most recently activated plate for their current checkpoint. Stepping on another plate linked to `first` changes the respawn location without advancing to the next checkpoint. This selection survives leaving, rejoining and server restarts in solo games. Races keep the selected plate for the current match and start fresh next match. Plates for future stages cannot skip checkpoint order, and plates for previous stages cannot move progress backward. Linked plates use centered respawn positions and the direction you faced when linking them.

Use `/lg admin unlink-checkpoint parkour first` while looking at a plate to remove that link. Unlinking the last plate keeps the checkpoint available for signs; use `remove-checkpoint` to remove the whole stage. If a selected link is removed, saved players fall back to the checkpoint's default spawn. Existing signs and automatically detected checkpoint plates still work. Explicitly linked plates take priority over the configured finish-plate material, so a linked heavy plate is a checkpoint rather than a finish trigger. Place finish plates separately.

Place a **heavy weighted pressure plate** at the finish. Any such plate inside the arena is a finish trigger. These materials can be changed in `config.yml`. Finish and checkpoint signs are also supported. Reusing a checkpoint ID moves its location without changing its order; remove and re-add it to change its order.

```text
/lg admin option parkour name Sky Steps
/lg admin option parkour description Reach the rooftop through every checkpoint.
/lg admin option parkour duration 1800
/lg admin enable parkour
/lg join parkour
```

Use `/lg restart` (or the confirmed Restart course dialog) to clear saved progress and reset the timer. Use `/lg revert` to return to your last checkpoint. Use `/lg leave` to save course progress, leave and return. Winning clears that course's saved checkpoint. Deleted checkpoints are ignored safely on later joins.

By default, players return to their exact pre-game location, including world and facing. `/lg admin set parkour exit` sets an explicit return location instead; it must be outside the arena. To return to the original location again, remove the arena's `exit` entry while the server is stopped.

## Set a player's checkpoint manually

Administrators and the server console can override checkpoint order for a player:

```text
/lg admin set-checkpoint parkour PlayerName second
```

This updates the player's saved checkpoint without teleporting them. While playing, they can use `/lg revert` or respawn to return there. You can assign a later or earlier checkpoint; their current elapsed time is preserved. To choose a particular linked plate, add its number (starting at 1, in link order):

```text
/lg admin set-checkpoint parkour PlayerName second 2
```

Append `teleport` to also move an active participant immediately:

```text
/lg admin set-checkpoint parkour PlayerName second 2 teleport
```

Omitting the plate number selects the checkpoint's default spawn. Teleporting requires the player to be actively playing that arena. A blocked teleport leaves the checkpoint saved so `/lg revert` can retry. Assignment does not finish the course or grant rewards.

For solo courses, you can also set saved progress for players who are not currently playing, using an online name, a name cached by the server, or an explicit UUID. Their next join resumes there. Races require the target player to be actively racing in that arena; changes apply to that match. Spectators and players awaiting recovery cannot have their current arena checkpoint changed. The command requires `lumigames.admin` and includes tab completion for online names, checkpoints and linked plate numbers.

## Multiplayer setup

```text
/lg admin create arena kills
/lg admin set arena corner1
/lg admin set arena corner2
/lg admin set arena start
/lg admin set arena lobby
/lg admin set arena spectator
/lg admin option arena min-players 2
/lg admin option arena max-players 12
/lg admin option arena target 10
/lg admin option arena duration 300
/lg admin loadout arena
/lg admin enable arena
```

`loadout` copies the administrator's current inventory, armor and offhand. It does not take the items. For team games, enable `teams` and set `red-spawn` and `blue-spawn`. CTF also requires `red-flag` and `blue-flag`: these are capture locations, which you can decorate with banners. The plugin tracks possession; it does not remove your decorative banners.

Named loadouts include current potion effects:

```text
/lg admin loadout arena archer
/lg admin loadout arena red
/lg admin loadout arena blue
```

Players choose named kits in their lobby dialog or with `/lg loadout archer`. Team kits named `red` and `blue` take priority. Loadout selection closes when the match starts. Save loadouts outside a minigame. Remove one with `/lg admin remove-loadout arena archer`.

For paintball, use a `KILLS` arena, save a snowball kit, and enable `paintball` and optionally `infinite-ammo`. Snowball and egg hits become lethal; friendly-fire rules still apply. Infinite ammo supports thrown snowballs/eggs and bows.

For treasure hunts, look at each desired block within six blocks and use `/lg admin set hunt treasure`. Treasure inventories are never opened or emptied. Use `/lg admin clear-treasures hunt` to clear the locations.

## Commands

Aliases: `/lumigames`, `/lg`, `/mg`, `/minigame`.

| Player command | Purpose |
| --- | --- |
| `/lg` | Game browser, or controls for your current game |
| `/lg join <arena>` | Join an enabled arena |
| `/lg spectate <arena>` | Spectate a multiplayer match |
| `/lg leave` / `quit` | Leave and restore state |
| `/lg revert` / `checkpoint` | Return to the current checkpoint or start |
| `/lg restart` | Restart a solo course and clear its saved checkpoint |
| `/lg team <red\|blue>` | Choose a balanced team while waiting |
| `/lg loadout <kit>` | Choose a lobby loadout |
| `/lg hint` | Treasure distance hint |
| `/lg info <arena>` | Arena information dialog |
| `/lg stats <arena>` | Personal results; arena can be omitted while playing |

Admin commands start with `/lg admin`:

| Action | Purpose |
| --- | --- |
| `create <id> <mode>` | Create a disabled arena |
| `edit <id>` | Open the arena editor |
| `set <id> <position>` | Set `start`, `lobby`, `exit`, `spectator`, `corner1`, `corner2`, `red-spawn`, `blue-spawn`, `red-flag`, `blue-flag`, or `treasure` |
| `checkpoint <id> <name>` | Save a checkpoint at your feet |
| `link-checkpoint <id> <name>` | Link the targeted pressure plate; repeat the same name for alternate spawns at one stage |
| `unlink-checkpoint <id> <name>` | Unlink the targeted plate while keeping its checkpoint |
| `set-checkpoint <id> <player\|UUID> <checkpoint> [plate-number] [teleport]` | Override a player's checkpoint; optionally select a linked plate and teleport an active participant |
| `remove-checkpoint <id> <name>` | Remove a checkpoint and all its plate links |
| `clear-treasures <id>` | Remove treasure locations |
| `loadout <id> [kit]` | Save current equipment; named kits also include effects |
| `remove-loadout <id> <kit>` | Delete a named loadout |
| `option <id> <key> <value>` | Change a rule, display name or instructions |
| `reward <id> <command>` | Append a winner console command, without `/` |
| `reward <id> clear` | Remove winner commands |
| `enable <id>` / `disable <id>` | Change availability |
| `start <id>` | Start a waiting match with enough players |
| `stop <id>` | End a match, restore participants and restore recorded blocks |
| `score <id> <player> <points>` | Add points to an active CUSTOM participant |
| `delete <id> confirm` | Remove an inactive arena definition |
| `recover` | Retry block and online-player recovery; stop matches first |
| `migrate <preview\|apply> [source]` | Preview or import old Minigames arena configurations; also available from console |

IDs contain 1–32 lowercase letters, digits, underscores or hyphens. Numeric rule keys: `min-players`, `max-players`, `duration`, `countdown`, `lives`, `target`. Boolean keys: `teams`, `pvp`, `break-blocks`, `place-blocks`, `late-join`, `flight`, `paintball`, `infinite-ammo`, `require-permission`. Text keys: `name`, `description`. Duration and countdown use seconds. Set `duration` to `0` to remove the time limit, for example `/lg admin option parkour duration 0`. A positive duration restores the limit. Stop active matches before editing. Unlimited games still track elapsed completion times and end when their objective is completed or an administrator stops them. Infection survivors cannot win by timeout when the limit is removed. Late joins are rejected for races, infection and elimination modes.

Edits require an inactive arena. Invalid enabled-arena edits are rejected; disable the arena before restructuring it. Enabling validates locations, boundaries, objectives, numeric limits and overlap with other enabled arenas.

Winner commands support `{player}`, `{uuid}` and `{arena}`. For example:

```text
/lg admin reward arena give {player} diamond 1
```

Reward commands run after successful player restoration and result persistence. They can invoke your existing economy or reward plugins. External console commands are not transactional: a server crash can interrupt a reward sequence. A finish waiting for a blocked teleport is retained in memory and awarded after recovery succeeds; a server crash before that recovery preserves player inventory but does not preserve the pending win/reward.

## Signs

Create signs as an administrator. Use the front face:

```text
[Lumigames]
join
parkour
```

Line 2 actions: `join`, `spectate`, `leave`/`quit`, `checkpoint`, `finish`, `revert`, `team`, `loadout`, `score`. Line 3 is the arena ID. Line 4 is the checkpoint ID or team name, where applicable. `loadout` refreshes the lobby kit. `score` works only in CUSTOM arenas and awards one point once per sign per participant. Other actions open arena details. `[Minigame]` is accepted as a marker, but this is not a general importer for legacy sign layouts or permissions.

## Persistence and recovery

- `arenas.yml`: arena settings, locations, loadouts, effects and winner commands.
- `progress.yml`: checkpoint IDs, selected checkpoint spawn locations, elapsed course time, plays, wins, kills, deaths and best completion times. Checkpoints and wins save immediately; other counters also save periodically and on shutdown.
- `recovery/<player UUID>.yml`: original inventory and equipment, location, game mode, experience, health, hunger, effects, flight and related survival state. Written atomically before entry. Recovery files are deleted only after restoration and `Player.saveData()` succeed.
- `rollback/<arena>.log`: append-before-change block data journal. Replayed when a match ends and at startup after a crash. Missing worlds keep their logs; load those worlds and run `/lg admin recover`.

Disconnecting exits the game and attempts restoration immediately. Failed teleports, unavailable worlds and deaths retain the recovery file; respawn or the next login retries. Online pending restores retry every second. A recovery failure locks game interactions rather than allowing temporary gear to escape. Keep recovery files until the affected players have recovered; they contain the original inventories.

Only simple solid blocks may be placed in games: stone, cobblestone, dirt, oak planks and white wool. Spleef only breaks snow blocks. Tile entities and container inventories cannot be changed by players. Fluid, piston, explosion, growth, fire and common entity block changes are blocked inside enabled arenas. Administrators can build in enabled arenas only while no match exists. Recorded block changes are capped by `max-recorded-blocks`.

Other commands are blocked during games except the allowlist in `config.yml`. External teleports make the player leave and return through recovery. Do not allowlist commands that transfer items or bypass game isolation.

## Permissions

| Permission | Default |
| --- | --- |
| `lumigames.play` | Everyone |
| `lumigames.spectate` | Everyone |
| `lumigames.admin` | Operators |
| `lumigames.arena.<id>` | Checked only when that arena enables `require-permission`; assign with your permission manager |

## Reference compatibility and limits

This is a new implementation of the principal gameplay systems, not a drop-in release of Addstar Minigames. The supplied reference also contains substantial server-specific infrastructure that this release does **not** reproduce:

- Import of legacy SQL statistics, player inventories/checkpoints or region scripts. Arena configuration migration is supported as described above.
- Vault betting/refunds, economy entry fees, direct item reward menus, and the legacy conditional reward schemes. Winner console commands provide basic integration.
- The old region/node scripting engine, custom Java mechanics API, party mode, global timed treasure-chest spawning, schematic/entity regeneration and block/entity recording beyond the protected simple-block implementation above.
- Resource-pack management, randomized chest inventories, legacy backend exports, bespoke scoreboard displays and every old command/permission alias.
- Floodgate/Cumulus Bedrock forms. Native dialogs require a compatible Java client.

The original `Minigames/` tree remains available for comparison. Avoid treating this JAR as a feature-complete or data-compatible replacement for an existing production Minigames installation without reviewing those differences.

## Validation

`./gradlew test` covers snapshots and recovery, checkpoint persistence/order, race fairness, failed return teleports, write failures, countdown cancellation, spectator counts, elimination on disconnect, bounds, arena validation and rule input handling. Migration tests cover the legacy layouts, preview behavior, exact backups, repeated imports, conflicts, missing worlds, invalid YAML, equipment slots, commit failures and command permissions. Tests use Mockito plus MockBukkit's registry provider; they do not start a real Paper server. See [SMOKE_TEST.md](SMOKE_TEST.md) for the remaining in-server checks, including dialog rendering and actual combat/respawn event ordering.

The native dialog implementation follows the [Paper dialog API](https://docs.papermc.io/paper/dev/dialogs/).
