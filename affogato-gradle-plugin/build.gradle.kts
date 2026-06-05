plugins {
    id("com.gradle.plugin-publish") version "2.1.1"
}

dependencies {
    implementation(project(":affogato-compiler"))
    implementation(project(":affogato-runtime"))
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    website = "https://github.com/alex-cova/affogato"
    vcsUrl = "https://github.com/alex-cova/affogato.git"

    plugins {
        create("affogato") {
            id = "dev.affogato"
            implementationClass = "dev.affogato.gradle.AffogatoGradlePlugin"
            displayName = "Affogato"
            description =
                "Gradle plugin for the Affogato JVM language: compiles .aff sources to Java and wires them into Java source sets."
            tags.set(listOf("affogato", "transpiler", "java", "jvm"))
        }
    }
}
