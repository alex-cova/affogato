plugins {
    `java-library`
    application
    antlr
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    api(project(":affogato-runtime"))
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("dev.affogato.compiler.cli.AffogatoCli")
}

val sourceSets = the<SourceSetContainer>()

tasks.test {
    // Forward update flags to the test JVM.
    System.getProperty("affogato.golden.update")?.let { systemProperty("affogato.golden.update", it) }
    System.getProperty("affogato.exec.update")?.let { systemProperty("affogato.exec.update", it) }
}

val antlrPackage = "dev.affogato.compiler.parser"
val antlrOutput = layout.buildDirectory.dir("generated-src/antlr/main")

val generateLexerGrammarSource by tasks.registering(AntlrTask::class) {
    description = "Generates the Affogato lexer (must run before the parser grammar)."
    val grammarDir = layout.projectDirectory.dir("src/main/antlr/dev/affogato/compiler/parser")
    source = fileTree(grammarDir) {
        include("AffogatoLexer.g4")
    }
    outputDirectory = antlrOutput.get().asFile
    arguments.addAll(listOf("-visitor", "-long-messages", "-package", antlrPackage))
}

tasks.withType<AntlrTask>().configureEach {
    arguments = arguments + listOf(
        "-visitor",
        "-long-messages",
        "-package",
        antlrPackage
    )
}

tasks.named<AntlrTask>("generateGrammarSource").configure {
    dependsOn(generateLexerGrammarSource)
    arguments = arguments + listOf(
        "-lib",
        antlrOutput.get().asFile.absolutePath
    )
    exclude("**/AffogatoLexer.g4")
}

jmh {
    fork = 1
    warmupIterations = 2
    iterations = 5
    benchmarkMode = listOf("thrpt")
    timeUnit = "s"
}

val selfTest by tasks.registering(JavaExec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the compiler JUnit suite through the legacy self-test entry point."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.affogato.compiler.AffogatoCompilerSelfTest")
}
