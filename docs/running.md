# Running Kiln

## First start

Put `kiln.jar` into an empty folder and start it with Java 25:

```bash
java -jar kiln.jar
```

On the first start Kiln writes `kiln.yml` and `permissions.yml` with a comment on every option, and creates
`plugins/` and `worlds/`. Make yourself operator in the console:

```bash
kiln op YourName
```

Type `kiln` in the console for every command. `stop` or Ctrl+C saves player data and shuts down.

## The folder

| Path | What it is | Back it up? |
|---|---|---|
| `kiln.yml` | Settings | yes |
| `permissions.yml` | Groups, their permissions and who is in them | yes |
| `ops.txt` | Operators, one name per line | yes |
| `plugins/` | Plugin jars and each plugin's folder with its config and texts | yes |
| `plugins/.loaded/` | Copies Kiln loads from, so jars can be replaced while it runs | no |
| `worlds/` | Maps, one folder each, never written to by Kiln | yes |
| `data/` | Player data and plugin tables when storage is `files` | yes |
| `logs/` | `latest.log` and one compressed file per day, kept for two weeks | if you need them |
| `pack.zip`, `server-icon.png` | Resource pack and server list icon, both optional | yes |

## One server per round

A round server starts, takes a handful of players, and goes away. The settings that make that work:

```yaml
world: arena          # the map from worlds/arena
stop-when-empty: 30   # shut down 30 seconds after the last player left
web-port: 8080        # /health tells the platform when players can join
```

Every option can also come from the environment: `KILN_` plus the name in capitals, with `.` and `-` as `_`.
`KILN_STOP_WHEN_EMPTY=30` is the same as the line above and wins over the file.

## In a container

The repository's Dockerfile builds Kiln and runs it with the web port on 8080. Everything that changes lives
in the volume `/server`:

```bash
docker build -t kiln .
```

```bash
docker run -p 25565:25565 -p 8080:8080 -v ./server:/server kiln
```

Each release also publishes an image as `ghcr.io/vxnsin/kiln`. Point the health check of your platform at
`http://<container>:8080/health`: it answers 200 once players can join and 503 while Kiln is starting.

## Many servers

Servers that share a Redis, Valkey or Dragonfly form a network on their own:

```yaml
server-id: lobby-1
storage:
  type: redis
  redis: "redis://:password@redis.internal:6379/0"
public-address: lobby-1.example.com:25565
```

- Player data and plugin tables are shared, so a player has the same coins on every server.
- `/kiln servers` lists every server with its players, `/kiln find <player>` says where someone is.
- Bans live in the shared storage, so a ban counts on every server.
- With `public-address` set, `/kiln send <player> <server>` and plugins move players between servers using
  Minecraft's transfer. No proxy is needed for that.

Without a public address per server, put a Velocity proxy in front and set `velocity-secret` to its
forwarding secret on every Kiln server. Players then only get in through the proxy.

## Watching it

- `/kiln performance` in the console or in game
- The dashboard at `http://<host>:<web-port>/`
- `http://<host>:<web-port>/metrics` for Prometheus, with tick time, players, memory, CPU, chunks, entities
  and each plugin's time per tick
- `/kiln profile 30` when something is slow and you want to know where

## Updating

`/kiln update` looks for a newer release on GitHub and downloads it next to the running jar. Stop the
server, replace `kiln.jar` with the new file and start again. The release notes say when plugins need a new
build.
