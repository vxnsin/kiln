The first release of Kiln: a Minestom server that is ready to run. Download `kiln.jar`, start it with
Java 25, and connect with Minecraft 26.2.

### While you build

- Plugins swap on their own when their jar changes, together with the plugins that depend on them, and a build
  that fails to start leaves the previous version running
- `java -jar kiln.jar new my-plugin` creates a plugin project that builds straight into the running server and
  publishes itself as a GitHub release when you push a tag
- `/kiln install owner/repo` fetches a plugin from a GitHub release
- `/kiln performance`, the dashboard and `/metrics` show each plugin's time per tick
- `/kiln profile` samples the server threads and names the busiest methods and plugins
- Player data with autosave, key-value tables, config files read into classes, and translated texts
- Rounds that wait for players, count down, start and end, chest menus with buttons, and sidebars from a list
  of lines

### While it runs

- A console with a pixel icon, a spinner per startup step, plugins as a tree, history and tab completion
- Everything under `/kiln`, and in game as a native Minecraft dialog with `/kiln menu`: plugins, players, kick, mute,
  ban, say, maintenance, groups, game mode, teleport, worlds,
  resource pack, config reload, update check and a stop with a countdown
- Permission groups with inheritance, wildcards and chat prefixes in `permissions.yml`
- Chat, join and leave messages and the tab list styled from `kiln.yml`, with your server name and colors
- A live dashboard on the web port, plus `/health`, `/stats` and `/metrics` for Prometheus
- A resource pack served by the server itself, and the kiln pixel icon in the server list
- Kiln's own screens in English or German, log files with daily rotation, autosave and a daily restart

### When there are many

- Worlds from `worlds/` as fresh read-only copies, one per round
- Storage in files, Redis, Valkey, Dragonfly or MongoDB, chosen in `kiln.yml`
- Servers sharing a Redis find each other: `/kiln servers`, `/kiln find`, messages between plugins
- `/kiln send` moves players between servers with Minecraft's transfer, no proxy needed
- Velocity forwarding, settings from environment variables, a health check, stop when empty or after a round, a Dockerfile and
  a container image on GitHub Packages

`kiln.jar` below is the server, `kiln-api.jar` the plugin API. The README, `docs/plugins.md` and
`docs/running.md` explain the rest.
