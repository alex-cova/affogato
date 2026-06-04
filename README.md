# Affogato

Affogato is an experimental JVM language that transpiles to Java. The current
implementation is a Gradle-oriented source-to-source compiler for Java 21 with
runtime annotations and IntelliJ plugin support.

## Current State

- `.aff` sources parse through an ANTLR grammar, are checked by the compiler,
  and transpile to Java source files.
- `dev.affogato` Gradle plugin registers Affogato compile tasks per Java source set and
  wires generated Java into the matching `compileJava` task.
- Java 21 is the initial target.
- The IntelliJ module uses the IntelliJ Platform Gradle Plugin plus
  Grammar-Kit/JFlex generation for lexer/parser classes, syntax highlighting,
  navigation, rename and compiler diagnostics.

## Production Subset

The current production target is small JVM apps and libraries that compile to
Java 21 source. The supported subset is intentionally frozen around:

- optional semicolons
- classes, records, enums and interfaces, including interface `default` methods
- constructors, compact constructors, fields, methods, local `var` / `let`
- `class Child : Parent` and interface implementation through the same `:`
  clause
- `constructor(...)` as the class constructor keyword
- `var name: Type`, `var name = expr`, `let name = expr`
- nullable and non-null markers: `String?`, `String!`
- `func` as a `void` method alias
- `println(...)`
- `public`, `private`, `protected`, `static`
- `override`
- `not(...)`
- `is` as `instanceof`
- `value as Type`
- named arguments for Affogato and Java calls, including overload resolution
- Java interop for constructors, methods, fields, inherited/default methods,
  static imports, lambdas, method references, generics and common wildcard cases
- Swift-style `guard condition else { ... }`
- `for ... in`, `while`, `return`, `throw`
- `switch` statements and expressions with `case ... ->` and `default ->`
- `try`, `catch`, multi-catch and `finally`
- annotations on types and members
- simple property reads and writes through generated getters/setters when the
  receiver type is known to the transpiler

See [docs/LANGUAGE_REFERENCE.md](docs/LANGUAGE_REFERENCE.md) for syntax,
nullability, Java interop, overload/named-argument behavior and diagnostic
codes.

## Known Limitations

- Java source generation is the only backend; there is no bytecode backend.
- The compiler has an internal typed expression AST scaffold, but Java emission
  still uses the existing validated Java-source transformation path while that
  AST is migrated incrementally.
- Full JLS overload corner cases, target-typed poly expressions and capture
  conversion are post-small-app work.
- Safe calls (`?.`), Elvis (`?:`) and not-null assertions (`!!`) are outside the
  production subset and report explicit `AFFOGATO_UNSUPPORTED_*` diagnostics.
- IntelliJ support is limited to syntax, navigation, rename and compiler
  diagnostics; formatter, completion, quick fixes and marketplace polish are
  deferred.

## Build

```bash
GRADLE_USER_HOME=.gradle ./gradlew build
```

## CLI

```bash
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:run --args="src/main/affogato build/generated/sources/affogato/main/java"
```

## Sample

```bash
cd affogato-samples/hello
GRADLE_USER_HOME=../../.gradle ../../gradlew run
```
# affogato
