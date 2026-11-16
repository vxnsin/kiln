plugins {
    `maven-publish`
}

dependencies {
    api(libs.minestom)
    api(libs.adventure.minimessage)
    api(libs.slf4j)
}

tasks.jar {
    archiveBaseName.set("kiln-api")
}

tasks.named<Jar>("sourcesJar") {
    archiveBaseName.set("kiln-api")
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "kiln-api"
            from(components["java"])
        }
    }
}
