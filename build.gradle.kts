plugins {
    base
}

allprojects {
    group = "dev.affogato"
    // `providers.gradleProperty` reads only an explicitly-set property (-Pversion or
    // gradle.properties); unlike `findProperty("version")`, it does not fall back to Gradle's
    // built-in default project version ("unspecified"), which previously defeated the `?:` below
    // and caused artifacts to be published under the bogus version "unspecified".
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
        }
    }
}

// Publish the library modules consumed at runtime (the compiler and the annotations runtime).
// The publication is created inside the JavaPlugin callback so the `java` software component
// already exists when wiring `from(components["java"])` — configuring it eagerly (as before)
// resolved the component to null and produced a POM with no jar. The Gradle plugin module is
// deliberately excluded: it publishes itself through the `plugin-publish` plugin, and adding a
// second publication at the same coordinates here produced the duplicate-publication warning.
configure(listOf(project(":affogato-runtime"), project(":affogato-compiler"))) {
    apply(plugin = "maven-publish")
    plugins.withType<JavaPlugin> {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
        }
    }
}
