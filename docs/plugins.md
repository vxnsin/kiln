# Writing plugins for Kiln

A Kiln plugin is a jar with a class that implements `Plugin` and a file called `kiln-plugin.yml`. Everything
else is plain [Minestom](https://minestom.net/docs): Kiln adds what every server needs and stays out of the
way for the rest.

## Start a project

In the folder of your server:

```bash
java -jar kiln.jar new my-plugin
```

You get a Gradle project that compiles against the server jar and copies itself into `plugins/` on every
build. With the server running, `./gradlew build` swaps the plugin within about a second.

## kiln-plugin.yml

```yaml
name: my-plugin          # letters, digits, - and _; also the folder name under plugins/
version: 1.0.0
main: myplugin.MyPlugin  # needs a public constructor without arguments
description: What it does
authors: [you]
depends: [economy]       # loaded before this one, and their classes are visible to it
```

## Lifecycle

```java
public final class MyPlugin implements Plugin {

    @Override
    public void enable(Kiln kiln) {
        // register events, commands and timers here
    }

    @Override
    public void disable() {
        // stop threads you started yourself; Kiln removes everything else
    }
}
```

`enable` runs when the plugin is loaded, on startup or later through `/kiln load`, `/kiln reload` or a new
jar in the folder. Plugins that depend on others are enabled after them.

## Events

```java
kiln.events().on(PlayerSpawnEvent.class, event -> {
    event.getPlayer().sendMessage("Hello!");
});
```

Listeners added this way are timed for `/kiln performance` and removed when the plugin is unloaded or
swapped. For filters, priorities and child nodes there is `kiln.events().node()`, the plugin's own Minestom
event node; it is cleaned up as well, but not timed.

Avoid `MinecraftServer.getGlobalEventHandler()` in plugins. Listeners there outlive a swap, and the old code
keeps running next to the new one.

## Commands and permissions

```java
Command heal = new Command("heal");
heal.setCondition(kiln.requires("myplugin.heal"));
heal.setDefaultExecutor((sender, context) -> {
    if (sender instanceof Player player) {
        player.setHealth(20);
    }
});
kiln.commands(heal);
```

`kiln.requires` hides the command from everyone without the permission. Permissions come from groups in
`permissions.yml`; operators and the console hold all of them. Check one anywhere with
`kiln.hasPermission(sender, "myplugin.heal")`.

## Data per player

```java
public static final class Stats {
    int wins;
    int games;
}

PlayerData<Stats> stats = kiln.playerData("stats", Stats.class, Stats::new);

kiln.events().on(PlayerSpawnEvent.class, event -> stats.of(event.getPlayer()).games++);
```

The value is loaded while the player connects, before they are in the world, and saved when they leave. Any
class with fields or any record works; it is stored as JSON. `stats.save(player)` writes right away when
something must not wait.

## Everything else that needs storing

```java
Table<Integer> highscores = kiln.store().table("highscores", Integer.class);
highscores.put("parkour-1", 4210);
int best = highscores.get("parkour-1").orElse(0);
```

Tables live in files, Redis or MongoDB, whichever the server owner chose in `kiln.yml`. The plugin does not
know and does not need to. Calls block until the storage answers, so keep them off hot paths or move them to a
virtual thread.

## Config

`kiln.config()` returns `plugins/<name>/config.yml` as a map. On first start the file is copied from
`config.yml` inside your jar, so ship one with comments.

```java
int rounds = ((Number) kiln.config().getOrDefault("rounds", 3)).intValue();
```

Or read it straight into a class. Dashed keys fill camel-case fields, and fields the file leaves out keep their
defaults:

```java
public static final class Settings {
    int rounds = 3;
    String arena = "arena";
    boolean announceWinner = true;   // announce-winner: true
}

Settings settings = kiln.config(Settings.class);
```

## Texts in every language

Put `src/main/resources/lang/en.yml`, `lang/de.yml` and so on into the jar:

```yaml
won: "<gold><name> won the round!</gold>"
menu:
  title: "Choose a map"
```

```java
kiln.messages().send(player, "won", Placeholder.unparsed("name", winner.getUsername()));
Component title = kiln.messages().get(player, "menu.title");
```

Each player gets the file for their Minecraft language, then the server's `language`, then English, then the
key itself. Server owners can override any file in `plugins/<name>/lang/`.

## Timers

```java
kiln.tasks().every(Duration.ofSeconds(1), () -> updateScoreboard());
kiln.tasks().after(Duration.ofSeconds(30), () -> endRound());
kiln.tasks().nextTick(() -> world.setBlock(0, 64, 0, Block.GOLD_BLOCK));
```

Timers end with the plugin and count towards its time in `/kiln performance`.

## A round on its own map

Worlds live as folders in `worlds/`, saved in singleplayer or on any other server. `open` gives a fresh copy
that never writes back, so every round starts on the same map.

```java
public void startRound(List<Player> players) {
    InstanceContainer arena = kiln.worlds().open("arena");
    Pos spawn = kiln.worlds().spawn("arena");
    players.forEach(player -> player.setInstance(arena, spawn));

    kiln.tasks().after(Duration.ofMinutes(5), () -> kiln.worlds().close(arena));
}
```

Set `player.setRespawnPoint(spawn)` as well when you move players: if someone falls out of the map, Kiln's
void rescue brings them back to their respawn point.

`close` sends everyone inside back to the default world and releases the map. Worlds a plugin opened are
closed for it when it is unloaded.

On a server that lives for one round only, set `stop-when-empty` in `kiln.yml`: once the last player is
gone the server shuts itself down, and the container platform starts a fresh one.

## Talking to other servers

When the server owner uses Redis, Valkey or Dragonfly as storage, Kiln servers that share it form a network.
A plugin can reach itself on every server:

```java
public record Invite(String from, String to) {}

kiln.network().subscribe("invite", Invite.class, invite -> {
    Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(invite.to());
    if (player != null) {
        player.sendMessage(invite.from() + " invited you to their party.");
    }
});

kiln.network().publish("invite", new Invite("alex", "sam"));
```

Handlers run on the server thread, so they can touch players and worlds directly. `kiln.network().servers()`
lists every server with its players, and `serverOf(name)` finds a player. Without Redis, messages only reach
the same server, so the code above works unchanged on a single server.

To move a player to another server, for example from the lobby into a free game:

```java
kiln.network().servers().stream()
        .filter(server -> server.id().startsWith("game-") && server.players().size() < server.maxPlayers())
        .findFirst()
        .ifPresent(server -> kiln.network().send(player, server.id()));
```

This uses Minecraft's transfer and needs no proxy. The target server needs `public-address` in its `kiln.yml`,
which is the address players use to reach it.

## Swapping safely

Kiln removes, on unload or swap: listeners from `kiln.events()`, commands from `kiln.commands()`, timers from
`kiln.tasks()`, worlds from `kiln.worlds()`, network subscriptions, and it saves player data. What it cannot
see is yours to stop in `disable()`: threads, executors, sockets, listeners on the global event handler.

If the new build throws in `enable`, Kiln starts the previous version again and says so in the console.

Static state does not survive a swap either. The new jar gets a new class loader, so keep anything that has
to outlive it in player data or a table.

## Depending on another plugin

```yaml
depends: [economy]
```

The dependency is enabled first, and its classes are visible to your plugin, so you can call its public API
directly. A plugin cannot be unloaded while another one still depends on it. When its jar is replaced, Kiln
takes the plugins that depend on it out as well and loads them again with it, so they see the new code.

## What the server owner sees

- Your plugin and its version in the startup tree and in `/kiln plugins`
- Its time per tick in `/kiln performance`, on the dashboard and in `/metrics`
- Its lines in the console, tagged with its name, through `kiln.logger()`
- If it fails to enable: a red line with the reason, while the server keeps running
