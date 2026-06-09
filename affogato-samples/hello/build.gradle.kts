buildscript {
    repositories {
        mavenLocal()
    }
}

plugins {
    application
    id("dev.affogato") version "0.1.0-SNAPSHOT"
}

application {
    mainClass.set("dev.affogato.samples.App")
}

affogato {
    sourceDirs.from("src/main/affogato")
}
