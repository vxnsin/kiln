<p align="center">
  <img src="docs/logo.svg" alt="Kiln" width="272">
</p>

<p align="center">
  A Minestom server that is ready to run. Start the jar, drop in plugins, done.
</p>

---

[Minestom](https://minestom.net) is a library, not a server. Before your first player joins you write a main
class, a console, commands, a config file, a plugin loader and somewhere to keep player data. Kiln is that
part, written once, so a new project starts at the interesting bit.

```
   ▄████▄
  ████████    Kiln 0.1.0
  ████████    a Minestom server that is ready to run
  ████████    Minecraft 26.2 · Java 25
 ██████████   made by vxnsin · github.com/vxnsin/kiln

  √ Engine   online mode
  √ World    flat
  √ Plugins  2 loaded
    ├─ lobby    1.2.0
    └─ parkour  0.4.1
  √ Ready    port 25565 · 2.8 s · type kiln for help
```

On the machine it was written on, an empty Kiln starts in under three seconds and sits at about 80 MB of
memory.

## What is in the box

- **Plugins.** Jars in `plugins/` with a small `kiln-plugin.yml`. They load in dependency order and are listed
  at startup. A plugin that fails shows up in red with the reason; the server keeps going.
- **Player data.** One line gives a plugin a value per player. It is loaded while the player connects and
  saved when they leave, as readable JSON files.
- **One command for the server itself.** `/kiln` with `plugins`, `players`, `mem`, `version`, `update`, `op`,
  `deop` and `stop`, in the console with tab completion and in game for operators.
- **One settings file.** `kiln.yml` is written on first start, with a comment on every option.
- **A place to stand.** A flat default world, so the server is joinable before any plugin exists. Plugins
  replace it by setting their own spawning instance.
- **Mojang authentication, MiniMessage and logging** set up and out of the way.

Kiln sits on top of Minestom and does not fork it. Events, commands, instances and entities are plain
Minestom, so its documentation applies unchanged.

## When Kiln fits

Kiln is for servers that are a game rather than a world: lobbies, minigames, event servers, parkour,
training and learning maps. They do not need survival mechanics. They need to start fast, stay small and be
put together from parts.

- **A server per round.** Three seconds and 80 MB make it practical to start a fresh server for every match
  and throw it away afterwards, instead of resetting a long-running one.
- **Parts instead of one binary.** With plain Minestom every game is its own program. With Kiln a lobby, a
  game mode and a stats plugin are three jars you combine per server.
- **Data that is just there.** Coins, stats and settings per player without writing load and save code.

If you want survival with mobs, farms and redstone, use [Paper](https://papermc.io). Minestom has none of
that built in, and neither has Kiln.

## Run it

You need Java 25. Build the jar:

```bash
git clone https://github.com/vxnsin/kiln
```

```bash
cd kiln
```

```bash
./gradlew :server:jar
```

The result is `server/build/libs/kiln-0.1.0.jar`. Put it in an empty folder and start it:

```bash
java -jar kiln-0.1.0.jar
```

Connect with Minecraft 26.2 to `localhost`. To make yourself operator, type `kiln op <your name>` into the console.

## Write a plugin

A plugin is a jar with one class and one file.

`src/main/resources/kiln-plugin.yml`

```yaml
name: hello
version: 1.0.0
main: com.example.HelloPlugin
depends: []
```

`HelloPlugin.java`

```java
public final class HelloPlugin implements Plugin {

    public static final class Visits {
        int count;
    }

    @Override
    public void enable(Kiln kiln) {
        PlayerData<Visits> visits = kiln.playerData("visits", Visits.class, Visits::new);

        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, event -> {
            Visits own = visits.of(event.getPlayer());
            own.count++;
            event.getPlayer().sendMessage("Visit number " + own.count);
        });
    }
}
```

That is a complete plugin with saved data. The full version, with a config file and a command, is in
[`example-plugin/`](example-plugin).

Install the API into your local Maven repository:

```bash
./gradlew :api:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("dev.vensin.kiln:kiln-api:0.1.0")
}
```

What a plugin gets from Kiln:

| Call | What it does |
|---|---|
| `kiln.playerData(name, type, fresh)` | A value per player, loaded on join and saved on leave |
| `kiln.store().table(name, type)` | Key-value storage for everything that is not per player |
| `kiln.config()` | `plugins/<name>/config.yml`, copied from the jar on first start |
| `kiln.dataFolder()` | `plugins/<name>/` for the plugin's own files |
| `kiln.logger()` | A logger whose lines are tagged with the plugin name |
| `kiln.isOperator(sender)` | Whether a command sender has admin rights |

## Folder layout

```
kiln-0.1.0.jar
kiln.yml        settings
ops.txt         operators, one name per line
plugins/        plugin jars and their config folders
data/           stored data, one folder per plugin
```

## Credits

Made by [vxnsin](https://github.com/vxnsin). Kiln would be nothing without
[Minestom](https://github.com/Minestom/Minestom) and [Adventure](https://github.com/KyoriPowered/adventure).

Kiln is not affiliated with Minestom, Mojang or Microsoft. Code is under the [MIT license](LICENSE).
