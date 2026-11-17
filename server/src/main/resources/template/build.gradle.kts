plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://vxnsin.github.io/kiln/maven")
}

dependencies {
    // The Kiln API brings Minestom and Adventure along; the server provides all of them at runtime.
    compileOnly("dev.vensin.kiln:kiln-api:{{kilnVersion}}")
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
