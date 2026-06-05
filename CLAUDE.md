# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

All commands use `GRADLE_USER_HOME=.gradle` to keep Gradle cache local.

```bash
# Full build (compiles all modules + runs tests)
GRADLE_USER_HOME=.gradle ./gradlew build

# Run the compiler JUnit suite only
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test

# Run the legacy compiler self-test entry point
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:selfTest

# Run the compiler CLI on a source tree
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:run --args="src/main/affogato build/generated/sources/affogato/main/java"

# Build and run the hello sample
cd affogato-samples/hello && GRADLE_USER_HOME=../../.gradle ../../gradlew run

# Build IntelliJ plugin sandbox (starts IDE with plugin loaded)
GRADLE_USER_HOME=.gradle ./gradlew :affogato-intellij-plugin:runIde
```

Note: `affogato-compiler:test` is the primary compiler coverage task. The
`selfTest` task remains for compatibility and delegates to the same JUnit suite
through `AffogatoCompilerSelfTest.main`.

Contributor workflow: `CONTRIBUTING.md` and `docs/DEVELOPER_GUIDE.md`. Update
goldens with `-Daffogato.golden.update=true`, exec fixtures with
`-Daffogato.exec.update=true`.

## Module Architecture

```
affogato-runtime          — @Nullable / @NotNull annotations only. No logic.
affogato-compiler         — ANTLR grammar → transpiler → Java source output.
affogato-gradle-plugin    — Gradle plugin (id "dev.affogato") wiring compileAffogato into compileJava.
affogato-intellij-plugin  — IntelliJ Platform plugin with Grammar-Kit/JFlex generated PSI.
affogato-samples/hello    — Sample project using the Gradle plugin.
```

### affogato-compiler pipeline

`AffogatoCompiler` drives two passes over all `.aff` files:

1. **Parse** — `AffogatoTranspiler.parse()` runs ANTLR (`Affogato.g4`) and builds a `ParsedUnit`.
2. **Register symbols** — `AffogatoTranspiler.registerSymbols()` populates class/field/method/constructor symbol tables across all units.
3. **Generate** — `AffogatoTranspiler.generate()` walks each `ParsedUnit` and emits `GeneratedJava` (package + class name + Java source string).

`AffogatoTranspiler` is one large class that handles parsing, symbol resolution, type checking, overload resolution, and Java code generation. It uses reflection (`URLClassLoader`) to inspect Java classpath dependencies at compile time for named-argument support.

`AffogatoDiagnostic` carries `(Severity, code, message, source path, line, column)` — the shape used both by the compiler and surfaced to Gradle/IntelliJ.

### affogato-gradle-plugin

`AffogatoGradlePlugin` registers a `AffogatoCompile` task per source set and wires its output directory as a Java source dir, so `compileJava` depends on `compileAffogato`. The `AffogatoExtension` exposes `sourceDirs`, `generatedSourcesDir`, `failOnWarnings`, and `javaRelease`.

### affogato-intellij-plugin

The IntelliJ plugin uses two generated source sets:
- `Affogato.flex` (JFlex) → `AffogatoLexerAdapter` (token types via `AffogatoTokenType`)
- `Affogato.bnf` (Grammar-Kit) → `AffogatoParser` + PSI classes under `dev/affogato/intellij/psi`

`generateLexer` and `generateParser` tasks must run before `compileJava`. The PSI layer is independent of the ANTLR grammar used by the compiler — the two grammars must be kept in sync manually.

## Key Design Constraints

- **Java 21 target** — compiler and all generated sources target Java 21 (`options.release.set(21)`).
- **Source-to-source only** — Affogato currently compiles to `.java` files, not bytecode. The `affogato-compiler` distribution (`installDist`) produces a runnable CLI.
- **Configuration cache enabled** — `org.gradle.configuration-cache=true` in `gradle.properties`; tasks must be configuration-cache compatible.
- **Two separate grammars** — `affogato-compiler` uses ANTLR (`Affogato.g4`); `affogato-intellij-plugin` uses Grammar-Kit/JFlex (`Affogato.bnf` + `Affogato.flex`). Changes to language syntax require updates in both.
