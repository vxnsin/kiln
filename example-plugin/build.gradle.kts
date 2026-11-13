dependencies {
    compileOnly(project(":api"))
}

tasks.jar {
    archiveBaseName.set("example-plugin")
}
