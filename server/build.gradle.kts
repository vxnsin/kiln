plugins {
    application
}

dependencies {
    implementation(project(":api"))
    implementation(libs.snakeyaml)
    implementation(libs.logback)
    implementation(libs.jline)
    implementation(libs.mongodb)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("dev.vensin.kiln.server.Main")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraftVersion").get())
    inputs.properties(props)
    filesMatching("version.properties") { expand(props) }
    // The plugin template ships its own Gradle wrapper, taken from this build's.
    from(rootProject.layout.projectDirectory) {
        include("gradlew", "gradlew.bat", "gradle/wrapper/**")
        into("template/wrapper")
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.layout.projectDirectory.dir("run").asFile
    doFirst { workingDir.mkdirs() }
    jvmArgs("-Dstdout.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}

// One runnable file: the server classes plus every dependency.
tasks.jar {
    archiveBaseName.set("kiln")
    manifest {
        attributes(
            "Main-Class" to "dev.vensin.kiln.server.Main",
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from(runtime.map { files -> files.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
