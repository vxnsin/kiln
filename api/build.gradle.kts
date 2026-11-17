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

base {
    archivesName.set("kiln-api")
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "kiln-api"
            from(components["java"])
            pom {
                name.set("Kiln API")
                description.set("The plugin API of Kiln, a Minestom server that is ready to run")
                url.set("https://github.com/vxnsin/kiln")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/vxnsin/kiln/blob/main/LICENSE")
                    }
                }
                scm {
                    url.set("https://github.com/vxnsin/kiln")
                }
            }
        }
    }
    repositories {
        // The release workflow passes the gh-pages checkout, which GitHub Pages serves as a Maven repository.
        findProperty("pagesRepo")?.let { folder ->
            maven {
                name = "pages"
                url = uri(folder)
            }
        }
    }
}
