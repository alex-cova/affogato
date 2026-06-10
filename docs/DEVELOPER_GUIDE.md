# Affogato Developer Guide

This guide is for contributors and tool authors working on the Affogato compiler,
Gradle plugin, and IntelliJ integration.

## Repository layout

| Module | Role |
|---|---|
| `affogato-runtime` | `@Nullable` / `@NotNull` annotations used in generated Java |
| `affogato-compiler` | ANTLR grammar, transpiler, CLI (`AffogatoCli`) |
| `affogato-gradle-plugin` | Gradle plugin id `dev.affogato` |
| `affogato-intellij-plugin` | Syntax, navigation, rename, compiler annotator |
| `affogato-samples/hello` | Minimal Gradle sample |

Language semantics and diagnostic codes: [LANGUAGE_REFERENCE.md](LANGUAGE_REFERENCE.md).

## Build and test

All commands assume a local Gradle cache:

```bash
export GRADLE_USER_HOME=.gradle   # optional; repo configures this in docs
```

```bash
# Full CI-equivalent build
GRADLE_USER_HOME=.gradle ./gradlew build

# Compiler tests only
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test

# Hello sample
cd affogato-samples/hello && GRADLE_USER_HOME=../../.gradle ../../gradlew run

# IntelliJ sandbox with plugin loaded
GRADLE_USER_HOME=.gradle ./gradlew :affogato-intellij-plugin:runIde
```

## Compiler test harnesses

Three fixture suites live under `affogato-compiler/src/test/resources/`:

| Harness | Class | Purpose |
|---|---|---|
| `golden/` | `AffogatoGoldenTest` | Byte-exact generated Java + `javac --release 21` |
| `negative/` | `AffogatoNegativeTest` | Expected error codes (`expected-diagnostics.txt`) |
| `lexer/` | `AffogatoLexerFixtureTest` | Lexer/syntax failures (`AFFOGATO_PARSE`, strict optional) |
| `lexer-valid/` | `AffogatoLexerValidFixtureTest` | Lexer smoke programs that must compile |
| `parser/` | `AffogatoParserFixtureTest` | Parser failures (`AFFOGATO_PARSE`, strict optional) |
| `parser-valid/` | `AffogatoParserValidFixtureTest` | Parser smoke programs that must compile |
| `exec/` | `AffogatoExecutionTest` | E2E: compile, `javac`, run `run()` or `main`, compare stdout/stderr |

### Updating golden Java

After an intentional codegen change:

```bash
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test \
  --tests dev.affogato.compiler.AffogatoGoldenTest \
  -Daffogato.golden.update=true
```

Review diffs under `golden/*/expected/` before committing.

### Updating execution stdout

```bash
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test \
  --tests dev.affogato.compiler.AffogatoExecutionTest \
  -Daffogato.exec.update=true
```

Execution fixtures may also include:

- `entry-point.txt` — `run` (default) or `main`
- `expected-stderr.txt` — optional stderr assertion
- `java/` — optional helper `.java` sources compiled before Affogato (for classpath interop)

### Lexer and parser fixtures

`lexer/`, `parser/`, `lexer-valid/`, and `parser-valid/` use the same layout as negative
fixtures (`expected-diagnostics.txt`, optional `expected-diagnostics-detail.txt` and
`expected-diagnostics-strict.txt`). Valid fixtures may include an empty marker file
`allow-empty-output` when the compiler should succeed without emitting Java (for example an
empty compilation unit).

### Negative fixtures

Each `negative/<case>/` directory contains:

- One or more `.aff` sources
- `expected-diagnostics.txt` — required error codes (one per line)
- `expected-diagnostics-detail.txt` — optional line/column/message checks

## Running the CLI

```bash
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:run -- \
  <source-dir> <output-dir> \
  [--classpath <jar-or-dir>]... \
  [--fail-on-warnings] \
  [--release 21]
```

Or use the installed distribution after `installDist`.

Diagnostics print with source snippets when the `.aff` file is readable (see
`AffogatoDiagnosticRenderer`).

## Two grammars (important)

The **compiler** uses ANTLR: `affogato-compiler/src/main/antlr/.../Affogato.g4`.

The **IntelliJ plugin** uses Grammar-Kit + JFlex:

- `affogato-intellij-plugin/src/main/grammar/Affogato.bnf`
- `affogato-intellij-plugin/src/main/jflex/Affogato.flex`

Lexer/parser classes are generated on `:affogato-intellij-plugin:compileJava`.
When you change surface syntax, update **both** grammars unless the change is
compiler-only.

Regenerate IDE parsers explicitly:

```bash
GRADLE_USER_HOME=.gradle ./gradlew :affogato-intellij-plugin:generateLexer :affogato-intellij-plugin:generateParser
```

## Gradle plugin behaviour

- `compileAffogato` runs before `compileJava` for each source set.
- On compiler errors the task **fails** and prints rendered diagnostics; no partial
  `.java` files are written.
- Options: `affogato { failOnWarnings = false; javaRelease = 21 }` on the extension.

## IntelliJ plugin

- **Navigation / rename** — Affogato declarations in `.aff` files.
- **Errors in editor** — `AffogatoExternalAnnotator` runs the real compiler with
  module classpath.
- **Completion** — keywords plus Affogato symbols (types, locals, parameters,
  fields, methods, members after `.`), import-line completion for Affogato/Java
  types, auto-import when selecting a cross-package type, Java classpath member
  completion via IntelliJ Java PSI, call-site named-argument / overload signature
  completion, relevance weighers, statement snippets (`sout` → `println()`,
  `main` → entry-point skeleton), and lenient completion when the caret is near a
  parse error.
- **Not yet** — static/wildcard import completion, parameter info / documentation
  on completion, quick fixes (see `AFFOGATO_ROADMAP.md`).

## Adding a language feature (checklist)

1. Update `Affogato.g4` and regenerate ANTLR (`compileJava` on compiler module).
2. Implement parsing → symbols → typecheck → codegen in `AffogatoTranspiler` or
   extracted passes.
3. Add `golden/` and/or `negative/` / `exec/` fixtures.
4. Update `docs/LANGUAGE_REFERENCE.md`.
5. Sync `Affogato.bnf` + `Affogato.flex`; run Grammar-Kit generation.
6. Run `./gradlew build`.
