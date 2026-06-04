plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":affogato-compiler"))
    implementation(project(":affogato-runtime"))
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("affogato") {
            id = "dev.affogato"
            implementationClass = "dev.affogato.gradle.AffogatoGradlePlugin"
        }
    }
}
