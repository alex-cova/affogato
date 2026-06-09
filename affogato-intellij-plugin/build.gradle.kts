import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.grammarkit") version "2023.3.0.3"
}

description = "IntelliJ language plugin for Affogato."

dependencies {
    implementation(project(":affogato-compiler"))

    intellijPlatform {
        intellijIdea("2026.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

grammarKit {
    jflexRelease.set("1.9.2")
    grammarKitRelease.set("2022.3.2")
}

tasks {
    generateLexer {
        sourceFile.set(file("src/main/jflex/Affogato.flex"))
        targetOutputDir.set(file("build/generated/sources/grammarkit/java/dev/affogato/intellij/lexer"))
        purgeOldFiles.set(true)
    }

    generateParser {
        sourceFile.set(file("src/main/grammar/Affogato.bnf"))
        targetRootOutputDir.set(file("build/generated/sources/grammarkit/java"))
        pathToParser.set("dev/affogato/intellij/parser/AffogatoParser.java")
        pathToPsiRoot.set("dev/affogato/intellij/psi")
        purgeOldFiles.set(true)
    }
}

sourceSets {
    main {
        java.srcDir("build/generated/sources/grammarkit/java")
    }
}

tasks.named("compileJava") {
    dependsOn("generateLexer", "generateParser")
}

tasks.named("sourcesJar") {
    dependsOn("generateLexer", "generateParser")
}
