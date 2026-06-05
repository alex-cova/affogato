# Affogato Robust Language Roadmap

## Goal

Turn the current MVP into a maintainable JVM language implementation with a real
parser, semantic model, Gradle integration, IntelliJ plugin scaffolding and a
test suite that protects language behavior.

> **Status (audited 2026-06-04):** `./gradlew build` is green; all four modules
> compile, the compiler JUnit suite passes, and the legacy compiler `selfTest`
> entry point delegates to that suite. This document was reconciled against the
> actual grammar, transpiler and test sources.

## Completed in the Initial MVP

- Multi-module Gradle build with `affogato-runtime`, `affogato-compiler`,
  `affogato-gradle-plugin` and `affogato-intellij-plugin`.
- A working source-to-source compiler for simple `.aff` files.
- `dev.affogato` Gradle plugin with `compileAffogato` wired into `compileJava`.
- Runtime `@Nullable` and `@NotNull` annotations.
- Basic IntelliJ `.bnf`, `.flex` and `plugin.xml` scaffold.
- Sample project at `affogato-samples/hello`.

## Completed Robustness Work

### Parser & compiler model

- [x] Replaced line-based parsing with an ANTLR grammar and generated parser
  (`Affogato.g4`).
- [x] Build a compiler model from parse trees before Java generation.
- [x] Semantic symbol tables for project classes, fields, methods and
  constructors.
- [x] Java classpath lookup (`URLClassLoader` reflection) for method parameter
  names and getter discovery.

### Overload resolution & generics

- [x] First-pass typed overload resolution for named-argument calls across
  Affogato and reflected Java executables, including common boxing/unboxing,
  primitive widening, reference assignability and Java varargs cases.
- [x] Java-like strict, loose and varargs applicability phases, with
  most-specific tie breaking for reflected Java executables and Affogato
  method/constructor symbols.
- [x] Practical generic method inference and generic return substitution for
  reflected Java methods, including owner type-variable substitution
  (`GenericBox<String>.get()`) and generic bound checks.
- [x] Overload support for Java static imports, Affogato inherited methods,
  reflected Java inherited/default methods, package-visible Java members from
  the same package, lambdas, method references and basic wildcard compatibility.
- [x] Ambiguity diagnostics for applicable overload sets where no candidate is
  more specific.

### Type checking & flow

- [x] Internal typed expression AST scaffold for production-subset expressions
  (literals, identifiers, calls, constructors, property access, assignments,
  binary/unary operators, casts, lambdas, method references and switch
  expressions). Java emission still migrates incrementally from the validated
  transformation path.
- [x] First-pass type checking for field/local initializers, return
  expressions, missing returns in non-void methods, simple assignments,
  immutable local reassignment, not-null null assignments and impossible casts.
- [x] Method-call resolution, boolean conditions, common binary expressions,
  missing properties on known receivers, Java public/package-visible fields and
  unknown constructor/type references.
- [x] Constructor applicability validation for Affogato and Java constructor
  calls, including shorthand `Type(...)`, explicit `new Type(...)`, collection
  aliases (`List<T>()`) and implicit no-arg Affogato constructors.
- [x] First-pass generic argument checks for declared assignments
  (`List<Integer>()` into `List<String>` fails before Java generation).
- [x] Direct Java field assignment validation, including field type
  compatibility and `final` field reassignment diagnostics.
- [x] Return-flow validation across exhaustive `if / else if / else` branches
  that return or throw in every branch.

### Language features (verified by JUnit)

- [x] Lambdas, method references, optional condition parentheses, named
  arguments, property access, `guard` flow validation, `for ... in`, `while`,
  `is` / `as`, nullability (`?` / `!`).
- [x] **Annotations** on classes, records and methods (`@Deprecated`,
  `@SuppressWarnings("unused")`).
- [x] **Interfaces** with `default` methods.
- [x] **Enums**.
- [x] **Records** with compact headers (`record Coord(x: int, y: int)`).
- [x] **Switch statements and switch expressions** (`case ... ->`, `default ->`,
  multi-label arms, block and expression arm bodies).
- [x] **try / catch / finally** (multi-catch via `Type1 | Type2`), exercised
  end-to-end by compiler tests.
- [x] **Static imports** (`import static ...JavaApi.identity`), exercised
  end-to-end by compiler tests.

### Gradle plugin

- [x] Multiple Java source sets, cacheable inputs/outputs and compiler options.
- [x] Configuration-cache compatible.
- [x] Gradle TestKit coverage for a single-project build and compiler failure
  behavior, including clear diagnostics and no partial generated Java after
  errors.

### IntelliJ plugin

- [x] Upgraded to the IntelliJ Platform Gradle Plugin with Grammar-Kit/JFlex
  generation tasks.
- [x] First-pass navigation, find usages and rename for project Affogato
  class-like declarations (`class`, `record`, `enum`, `interface`), fields,
  methods and parameters (`setName` in
  `AffogatoNamedElementImpl`).
- [x] **Compiler diagnostics in the editor** — `AffogatoExternalAnnotator` runs
  the real compiler and surfaces `AffogatoDiagnostic`s with severity.
- [x] IntelliJ Grammar-Kit/JFlex syntax is synchronized with the current ANTLR
  surface for records, enums, interfaces, annotations, static imports,
  switch, try/catch/finally, loops and modern operators.

## Developer experience (recent)

- [x] `CONTRIBUTING.md` and `docs/DEVELOPER_GUIDE.md` (build, fixtures, dual grammar, CLI).
- [x] CLI `--help`, repeatable `--classpath`, testable `AffogatoCli.run` exit codes.
- [x] Gradle `compileAffogato` fails with rendered diagnostics (`GradleException`).
- [x] IntelliJ lexer keywords extended (`abstract`, `break`, `continue`, `++`, `--`, `%=`).

## Remaining Work

### Compiler / semantics

- [ ] Move type-checker validation into a dedicated semantic pass over a real
  expression AST (the AST scaffold exists, but flow checks still lean on
  last-statement inspection) and validate more compound expressions before Java
  generation.
- [ ] Full Java type inference and generic substitution.
- [ ] Overload-resolution JLS corner cases: target-typed poly expressions,
  capture conversion / intersection types and rare maximally-specific abstract
  method tie breaks.

### IntelliJ PSI features (beyond the current annotator/navigation slice)

- [ ] Formatter, brace matcher and code style.
- [ ] Inspections and quick fixes.
- [ ] Java/import-aware references and code completion.

### Backend & distribution

- [ ] Bytecode/backend work, if Affogato moves beyond Java source generation.
- [ ] Publication/signing flows for Gradle Plugin Portal and JetBrains
  Marketplace.

## Maintenance Notes

- The compiler ANTLR grammar (`Affogato.g4`) and the IntelliJ Grammar-Kit/JFlex
  grammars (`Affogato.bnf` + `Affogato.flex`) are **separate** and must be kept
  in sync by hand whenever language syntax changes.
- `affogato-compiler:test` is the primary compiler coverage task. The
  `selfTest` task remains as a compatibility entry point and runs the same
  JUnit suite via `AffogatoCompilerSelfTest.main`.
