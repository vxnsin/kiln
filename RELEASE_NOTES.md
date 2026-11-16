The first release of Kiln: a Minestom server that is ready to run. Download `kiln-<version>.jar`, start it with
Java 25, and connect with Minecraft 26.2.

### While you build

- Plugins swap on their own when their jar changes, together with the plugins that depend on them
- `java -jar kiln.jar new my-plugin` creates a plugin project that builds straight into the running server
- `/kiln performance`, the dashboard and `/metrics` show each plugin's time per tick
- `/kiln profile` samples the server threads and names the busiest methods and plugins
- Player data, key-value tables, config files and translated texts in one line each

### While it runs

- A console with a pixel icon, a spinner per startup step, plugins as a tree, history and tab completion
- Everything under `/kiln`: plugins, players, kick, ban, say, maintenance, groups, game mode, teleport, worlds,
  resource pack, config reload, update check and a stop with a countdown
- Permission groups with inheritance, wildcards and chat prefixes in `permissions.yml`
- Chat, join and leave messages and the tab list styled from `kiln.yml`, with your server name and colors
- A live dashboard on the web port, plus `/health`, `/stats` and `/metrics` for Prometheus
- A resource pack served by the server itself, and the kiln pixel icon in the server list

### When there are many

- Worlds from `worlds/` as fresh read-only copies, one per round
- Storage in files, Redis, Valkey, Dragonfly or MongoDB, chosen in `kiln.yml`
- Servers sharing a Redis find each other: `/kiln servers`, `/kiln find`, messages between plugins
- `/kiln send` moves players between servers with Minecraft's transfer, no proxy needed
- Velocity forwarding, settings from environment variables, a health check, stop when empty, a Dockerfile and
  a container image on GitHub Packages

The plugin API is `kiln-api-<version>.jar` in the assets below. See the README and `docs/plugins.md`.
