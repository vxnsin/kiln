plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    // The server jar itself holds the Kiln API, Minestom and Adventure.
    compileOnly(files("{{kilnJar}}"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("{{name}}.jar")
    finalizedBy("deploy")
}

// A running Kiln server notices the new jar and swaps the plugin within a second.
tasks.register<Copy>("deploy") {
    from(tasks.jar)
    into("{{plugins}}")
}
