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
    mainClass.set("com.alexcova.teenyjson.App")
}

affogato {
    sourceDirs.from("src/main/affogato")
}

sourceSets {
    val api by creating {
        java.srcDirs("src/main/java")
    }
    main {
        java.setSrcDirs(files())
        compileClasspath += api.output
        runtimeClasspath += api.output
    }
}

dependencies {
    implementation(sourceSets.named("api").map { it.output })
}

tasks.register("printAffogatoClasspath") {
    doLast {
        val compileAffogatoTask = tasks.named<dev.affogato.gradle.AffogatoCompile>("compileAffogato").get()
        println("Affogato SourceDirs: ${compileAffogatoTask.sourceDirs.files}")
        println("Affogato Classpath:")
        compileAffogatoTask.classpath.files.forEach {
            println("  - $it (exists: ${it.exists()})")
        }
    }
}
