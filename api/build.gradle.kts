plugins {
    `maven-publish`
}

dependencies {
    api(libs.minestom)
    api(libs.adventure.minimessage)
    api(libs.slf4j)
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
