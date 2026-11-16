# Contributing

Thanks for wanting to help. Kiln is small on purpose, so the bar for new parts is "most servers need it".

## Build and run

You need Java 25; Gradle comes with the repository.

```bash
./gradlew build
```

builds everything and runs the tests. To try the server, `./gradlew :server:run` starts it in `run/`.

## Before you open a pull request

- One change per pull request, with a sentence on why it is needed
- `./gradlew build` passes
- New behaviour comes with a test where it can be tested without a Minecraft client
- Code follows the style around it: English, few comments, a comment only where the code cannot say it

## Reporting a bug

Open an issue with the Kiln version (first lines of the console), what you did, what you expected and what
happened. The full console output helps most.
