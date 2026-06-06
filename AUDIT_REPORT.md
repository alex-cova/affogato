# Affogato Audit Report

> Generated: 2026-06-05  
> Auditor: Senior Compiler Engineer (AI)  
> Scope: Full project — lexer, parser, AST, type checker, codegen, tests, architecture, performance, diagnostics

---

## Executive Summary

- **Overall state:** A well-structured, working source-to-source transpiler targeting Java 21. The ANTLR grammar is solid, the three-pass pipeline (parse → typecheck → generate) is architecturally sound, and the test suite (184 golden + 64 exec + 50 negative + 11 lexer) covers a wide surface area. The project is in strong alpha state with real usability.
- **Main risk:** Correctness fragility in the `ExpressionRenderer` / `AffogatoJavaGenerator` codegen path. Two known unimplemented production features (`?.` / `?:` / `!!`) have AST nodes and rendering code that are partially wired but guarded only by pre-scan diagnostics. Property mutation in expression position generates a diagnostic but the rendering fallback in `ExpressionRenderer` still executes a code path using non-deterministic hash-based temp variable names. The `scanUnsupportedToken` string scanner is naive and will false-positive if `?.`, `?:`, or `!!` appear as literal text inside an interpolated string containing nested quotes.
- **Top priority:** (1) Fix `AFFOGATO_RETURN_FLOW` double-fire and wrong location (always `1:1`). (2) Fix `scanUnsupportedToken` naive string skip not handling nested quotes in interpolation. (3) Remove or gate the non-deterministic `$safe_` / `$elvis_` temp-variable codegen in `ExpressionRenderer`. (4) Fix `validateTypeRef` hardcoded `1, 1` positions throughout `typeCheckExecutable`.
- **Current solidity level:** 7 / 10
- **Target for "rock-solid":** 9 / 10

---

## Critical Findings

| Severity | Area | Finding | Impact | Recommendation |
|---|---|---|---|---|
| **HIGH** | Diagnostics / TypeChecker | `AffogatoTypeChecker.typeCheckExecutable` emits `AFFOGATO_RETURN_FLOW` at `line=1, col=1` regardless of the actual method declaration position. The same diagnostic can be emitted a second time from `AffogatoJavaGenerator.writeMethods` (lines 277, 593) if the typecheck error does not abort the run. | Users see the error pointing to the top of the file instead of the method. Potential duplicate diagnostic. | Thread the declaration line through `typeCheckExecutable` and remove the redundant check from `writeMethods`. |
| **HIGH** | Lexer / String Scanner | `scanUnsupportedToken` in `AffogatoParserRunner` uses a naive string-skip that only handles `\"`-escaping. When the source contains `"${m["k"]}"` (real string inside interpolation, supported by the recursive `NestedStringLiteral` fragment), the inner `"` terminates the scanner's string-tracking, leaving `[` and `]` as apparent top-level code. This could cause false `AFFOGATO_UNSUPPORTED_*` diagnostics, or silently miss a real `?.` inside complex interpolation. | False errors or missed detections for code using `?.`, `?:`, `!!` inside interpolation strings. | Rewrite `scanUnsupportedToken` to use the same interpolation-aware nesting logic as `deepInterpolationIndex`, or switch to token-stream scanning instead of raw character scanning. |
| **HIGH** | Codegen / ExpressionRenderer | `ExpressionRenderer.render` generates temp variable names `$safe_N` and `$elvis_N` using `Math.abs(receiverText.hashCode() % 1000)`. These names are (1) non-deterministic across JVM runs — breaking golden tests — and (2) the generated code uses undeclared variables because `$safe_N` is never declared before the expression. The generated Java is syntactically invalid. | If `SafeCallExpression` or `ElvisExpression` nodes ever reach the renderer, the generated Java will not compile. | Either remove these rendering branches entirely (since these features are declared unsupported), or promote them to fully supported features with proper block-level temp-variable declarations. |
| **HIGH** | Semantics / Grammar gap | `QUESTION_DOT` (`?.`) appears in the `postfixPart` parser rule and is lexed correctly, but `ExpressionSemanticChecker.buildPostfix` has no branch for it. If `?.` slips past `scanUnsupportedToken` (e.g., inside interpolation), ANTLR would parse it as a postfix part, `buildPostfix` would fall through to the `DOT` branch, and `?.member` would be silently miscompiled as `.member`. | Silent miscompile if `?.` ever reaches codegen. | Add an explicit branch in `buildPostfix` for `QUESTION_DOT` that returns `UnsupportedExpression`. |
| **MEDIUM** | Diagnostics / Column accuracy | `AffogatoTypeChecker.typeCheckExecutable` calls `validateTypeRef(returnType, unit, 1, 1)` and parameter type checks with `(1, 1)` hardcoded. All type-reference errors for method signatures emit `1:1` in the diagnostic. | Users see type errors pointing to the wrong location. | Pass the `MethodDecl.line()` / parameter token positions to these calls. |
| **MEDIUM** | Codegen / ExpressionRenderer | Compound assignment (`+=`) on a complex receiver (e.g., `make().n += 1`) reads via `receiverText + "." + accessor + "()"` — calling `make()` twice: once for the getter, once for the setter. | Side-effecting receivers are evaluated twice — semantic inconsistency. | Introduce a temp variable for the receiver when the receiver expression is not a simple identifier. |
| **MEDIUM** | Codegen / Generator | `topLevelAssignmentStart`, `lastTopLevelDot`, `matchingOpenIndex`, and `splitTopLevel` use a simple `previous != '\\'` escape check that does not handle `\\` sequences correctly. `"test\\\\"` would leave the string prematurely closed. | Complex string expressions containing `\\` could cause misidentified operators and silently wrong code generation. | Use the ANTLR AST (already available post-typecheck) for all structure-sensitive text operations, instead of re-scanning raw strings. |
| **LOW** | Architecture | `AffogatoJavaGenerator` is 2,545 lines and `AffogatoTypeChecker` is 2,572 lines. Both mix parsing, symbol lookup, type inference, diagnostic emission, and code emission. The shared-mutable-state pattern (`setActiveTypeParams`) is fragile if the compiler is ever made multi-threaded. | Maintainability drag, increased regression risk. | Incrementally extract: `TypeInferenceService`, `OverloadResolver`, `StringInterpolationLowerer`. Eliminate `setActiveTypeParams` by passing as a parameter. |
| **LOW** | Performance | `ExpressionSemanticChecker.parseViaAntlr` is called once per expression in typecheck AND once per expression in generate. For a method with N expressions: 2N full ANTLR sub-parses. | Compilation of large files (like `Gemma4.aff`) is slow — quadratic in practice. | Cache parsed `AstExpression` from typecheck pass and reuse it during generate. |

---

## Untested Cases

| Area | Feature | Missing Case | Risk | Recommended Test |
|---|---|---|---|---|
| String Interpolation | Nested quotes in interpolation | `"${m["key"]}"` through `scanUnsupportedToken` (scanner-side) | HIGH | Unit test ensuring `scanUnsupportedToken` does not false-positive on `"${a["b"]}"` |
| Return Flow | Method with only `throw` as exit | `run(): int { throw RuntimeException("x") }` — should not fire `AFFOGATO_RETURN_FLOW` | MEDIUM | Add golden/exec fixture with throw-only methods |
| Return Flow | Diagnostic location accuracy | Verify `AFFOGATO_RETURN_FLOW` points to the method declaration line, not line 1 | HIGH | Add test checking `diagnostic.line()` matches method declaration |
| Property Mutation | Compound receivers (`make().n += 1`) | Double-evaluation of side-effecting receivers | HIGH | Add exec fixture comparing before/after call-count side effects |
| Property Mutation | Prefix inc/dec in expression | `println(++c.n)` — only postfix currently tested in negative fixture | MEDIUM | Add to `negative/property-mutation-in-expression/` |
| For Loop | C-style 3-part `for` | `for (var i = 0; i < 10; i++)` — grammar allows it via `forContent: expression` | MEDIUM | Add exec fixture |
| Labeled break/continue | `break label` / `continue label` | Not in grammar; confirm clean rejection error | LOW | Add negative fixture |
| Extension Functions | Unknown receiver type | Extension on `UnknownType.method()` | MEDIUM | Add negative fixture |
| Enums | Methods on enums | Enum body with method declarations | HIGH | Add golden fixture |
| Type Checker | `AFFOGATO_RETURN_FLOW` with switch as sole return | `return switch(...) { ... }` exhaustive coverage | MEDIUM | Add golden fixture |
| Diagnostics | `AFFOGATO_RESERVED_IDENTIFIER` on method names | `func synchronized() {}` | MEDIUM | Add to reserved-identifier negative fixture |
| Generics | Recursive type bounds success case | `<T: Comparable<T>>` method resolution working | HIGH | Add golden (negative already exists, positive missing) |
| Nullability | Non-null field with no initializer | `let name: String!` without init should error | MEDIUM | Add negative fixture |
| Unicode | Non-ASCII identifiers | `var café = 1` | LOW | Add `lexer-valid/unicode-identifier` smoke test |
| Switch | No default branch | Type-safety of exhaustiveness on non-enum type | LOW | Add negative/warning fixture |

---

## Unimplemented or Incomplete Features

| Feature | Current State | Evidence | Recommendation |
|---|---|---|---|
| Safe call (`?.`) | Rejected at pre-scan with `AFFOGATO_UNSUPPORTED_SAFE_CALL`; grammar includes it; `buildPostfix` silently ignores `QUESTION_DOT` | `AffogatoParserRunner.scanUnsupportedSourceEdges`; grammar `postfixPart: QUESTION_DOT` | Either remove `QUESTION_DOT` from grammar, or add a `buildPostfix` branch returning `UnsupportedExpression` |
| Elvis operator (`?:`) | Rejected at pre-scan; but `buildElvis` builds a real `ElvisExpression` AST node | `ExpressionSemanticChecker` lines 176–180 | Remove from grammar or add dedicated `UnsupportedExpression` path |
| Not-null assertion (`!!`) | Rejected at pre-scan | Same | Same |
| Do-while loops | Not in grammar; not in spec | No `DO` keyword in `AffogatoLexer.g4` | Document explicitly as out-of-scope or add to roadmap |
| Labeled break/continue | Not in grammar | No `Identifier COLON statement` production | Document as out-of-scope |
| Full return-flow analysis for exhaustive switch | `FlowAnalyzer.statementExits` has no switch exhaustiveness check | `FlowAnalyzer.java` — no `switchStatement` branch in `statementExits` | Add exhaustive switch detection to `blockExits` |
| Enum body methods | Enums registered as constants-only | `AffogatoJavaGenerator.generateEnum` — no method list on `ParsedEnum` | Add `List<MethodDecl>` to `ParsedEnum` and emit them |
| Sealed classes / pattern matching | Not in grammar or spec | No `SEALED`, `PERMITS`, `WHEN` keywords | Roadmap item; document clearly |
| Text blocks (Java 15+) | Not in grammar | No `"""` in lexer | Roadmap item |
| `throws` clause | No `throws` keyword in method declarations | Grammar `methodSignature` has no throws clause | Notable Java interop gap when overriding checked-exception methods |
| Abstract classes | `ABSTRACT` modifier on class/method parsed and emitted | `AffogatoJavaGenerator.writeMethods` emits `abstract` keyword | Missing end-to-end test coverage |

---

## Java Generation Problems

| Case | Generated Java | Problem | Fix |
|---|---|---|---|
| `SafeCallExpression` renderer | `(($safe_N = recv) != null ? $safe_N.prop : null)` | (1) `$safe_N` never declared; (2) `N` is `Math.abs(hashCode() % 1000)` — non-deterministic | Remove this branch or emit a block-level `var $safe_N = recv;` before the expression |
| `ElvisExpression` renderer | `(($elvis_N = left) != null ? $elvis_N : right)` | Same undeclared-variable and non-deterministic naming | Same fix |
| Compound assignment on complex receiver | `make().setN(make().getN() + (1))` | `make()` called twice when receiver is not a simple identifier | Hoist receiver to a temp variable |
| Extension function receiver parameter | `public static String shout(String $this, ...)` | Any user parameter named `$this` would collide | Reserve `$this` in `JAVA_RESERVED_WORDS` or rename to `receiver` |
| `static let` field getter | Generates non-static getter for static final field | Getter is non-static, inconsistent | Static fields should generate `static` getters |
| Accessor generation for private fields | Every field unconditionally gets a `public` getter/setter | Fields marked `private` still get public accessors — encapsulation broken | Respect `FieldDecl.access` modifier when generating accessors |
| `AFFOGATO_RETURN_FLOW` at line 1:1 | Diagnostic with wrong location | Location always `1:1` in type-check pass | Thread actual declaration position |
| Duplicate `java.util.Objects` import | Injected when `requiresRuntimeCheck()` is true | If already imported explicitly, a duplicate import is emitted | Check `unit.imports()` before injecting the runtime import |

---

## Semantic Problems

| Case | Current Behavior | Expected | Recommendation |
|---|---|---|---|
| `AFFOGATO_RETURN_FLOW` location | Always `line=1, col=1` | Should point to method's declaration line | Pass `MethodDecl.line()` to the diagnostic |
| `for-in` variable scope leak | `context.mutableVariables.put(variable, true)` in typecheck and generate, but `restoreScope(loopScope)` does not restore `mutableVariables` | Loop variable should not be mutable after the loop | `ScopeSnapshot` should capture `mutableVariables` |
| `FlowAnalyzer.statementExits` misses switch exhaustiveness | Exhaustive switch without explicit default does not satisfy `blockExits` | Should be treated as exiting when all enum constants are covered | Implement exhaustive-switch detection |
| Unused variable warning absent | No `AFFOGATO_UNUSED_LOCAL` or similar warning | Typical compiler warns on declared-but-unused locals | Add as optional warning |
| `String` vs `java.lang.String` | Multiple places compare `type.javaType().equals("String")` without handling `java.lang.String` | `let s: java.lang.String = ...` might not hit string-promotion paths | Normalize string types through a canonical form helper |
| Enum constant access type inference | `enumConstantAccessType` only works with a single `.` in the expression, guarded by `expression.indexOf('(') >= 0` — `Outer.Inner.CONST` returns `unknown` | Should resolve nested enum constants | Improve qualifier-strip logic |

---

## Performance

| Area | Problem | Impact | Recommended Fix |
|---|---|---|---|
| `parseViaAntlr` called per-expression, twice | Called once in typecheck AND once in generate per expression. For N expressions: 2N ANTLR parses of substrings. | Quadratic compilation time on large files. | Cache parsed `AstExpression` from typecheck pass by expression source string; reuse during generate. |
| `JavaResolver` class loading | `loadClass` checks `classCache` (a `HashMap`) but the fallback tries FQN, then package-qualified, then import-starred — each miss hits the `URLClassLoader`. | Repeated lookups for the same type trigger multiple ClassLoader calls. | Populate `classCache` on first miss so subsequent lookups are O(1). |
| Three separate `scanUnsupportedToken` scans | Three linear scans over the source text (for `?.`, `?:`, `!!`). | Triple character-scan overhead for unsupported operator detection. | Merge into a single scan or use token-stream inspection. |
| `ClassSymbolTable.lookup` simple-name fallback | The last-resort "unique simple name" loop iterates all registered FQNs linearly. | O(N_types) per lookup in large multi-file projects. | Build a secondary `Map<String, List<ClassSymbol>>` indexed by simple name at registration time. |
| `deepInterpolationIndex` + ANTLR lexer | Two separate passes over the source before any parsing. | Constant overhead per file. | Merge into the ANTLR error listener or eliminate by configuring ANTLR's ATN prediction budget. |

---

## Diagnostics Quality

| Current Error | Problem | Recommended Error |
|---|---|---|
| `AFFOGATO_RETURN_FLOW: Method foo must exit with a value on all paths. at Foo.aff:1:1` | Location always `1:1` | `AFFOGATO_RETURN_FLOW: Method foo must exit with a value on all paths. at Foo.aff:12:5` (actual declaration line) |
| `AFFOGATO_PROPERTY_MUTATION_EXPR: Mutating property c.n with ++/--/+= is not supported inside an expression` | Good message, but doesn't include the specific operator | Include specific operator: `"Mutating property 'c.n' with '++' ..."` |
| `AFFOGATO_TYPE_RESOLUTION: Cannot resolve type 'Coustomer'` | Spell-check suggestion system exists but confirm it fires for return types, field types, parameter types in all positions | Verify suggestion fires across all type-position contexts |
| `AFFOGATO_RETURN_FLOW` from both typecheck (line 180) and generate (lines 277, 593) | Potential duplicate diagnostic | Suppress the generate-pass check once typecheck has run (gate on `session.typesChecked()`) |
| `AFFOGATO_PARSE: Source is too deeply nested to parse` | Correct, but no location information beyond `1:1` | For `StackOverflowError` catch, return the best available token location from the ANTLR token stream |

---

## Architecture

**What's good:**
- Clear three-pass pipeline in `AffogatoCompiler`: parse → register → typecheck → generate → write, with `failIfNeeded` gates between phases. No partial file writes on errors.
- `AffogatoTranspiler` is a thin facade (57 lines) delegating to `CompilationSession`, `AffogatoJavaGenerator`, and `AffogatoTypeChecker`. Good separation at the entry point.
- `AstExpression` sealed interface hierarchy with records is idiomatic Java 21. The ANTLR-backed `ExpressionSemanticChecker.parseViaAntlr` gives accurate parse trees.
- `CompilationSession` cleanly owns the lifecycle of `AffogatoSymbolResolver` (which holds the `URLClassLoader`) and implements `AutoCloseable`. Try-with-resources in `AffogatoCompiler.compile` ensures classloader cleanup.
- `AffogatoDiagnosticCodes` centralized hint map is a good pattern; `AffogatoDiagnosticRenderer` with snippet+caret+hint is high quality.
- `GrammarSyncTest` enforces keyword/operator sync between the ANTLR and IntelliJ JFlex/BNF grammars automatically — an excellent preventive safeguard.
- `packageDirectory` path traversal protection (normalize + startsWith check) is correctly implemented.

**What's weak:**
- `AffogatoJavaGenerator` (2,545 lines) and `AffogatoTypeChecker` (2,572 lines) are too large. Both mix concerns: type inference, regex-based string parsing, overload resolution, and code emission all live in the same files.
- `ExpressionRenderServices` interface is a backwards callback from `ExpressionRenderer` into `AffogatoJavaGenerator`, creating tight bidirectional coupling that blurs the boundary between type checking and code generation.
- `AFFOGATO_RETURN_FLOW` check is duplicated: once in `AffogatoTypeChecker.typeCheckExecutable` and again in `AffogatoJavaGenerator.writeMethods` and `generateExtensionsHolder`. The generate-pass check is unreachable dead code in the normal flow (typecheck errors abort via `failIfNeeded`).
- `topLevelAssignmentStart`, `lastTopLevelDot`, `matchingOpenIndex`, `splitTopLevel`, `findMatching`, `findMatchingBraceSkippingStrings` are all custom string-scanning utilities that re-implement what the ANTLR AST already provides. This dual-track approach (text scan + AST) is a source of edge-case bugs.
- `setActiveTypeParams` mutable shared state is fragile for any future multi-threaded or incremental compilation scenario.

**Recommended changes (priority order):**
1. Fix `AFFOGATO_RETURN_FLOW` location and remove the duplicate from `AffogatoJavaGenerator`.
2. Remove or fix `SafeCallExpression` / `ElvisExpression` rendering branches in `ExpressionRenderer`.
3. Rewrite `scanUnsupportedToken` to be interpolation-aware (matching `deepInterpolationIndex` approach).
4. Add `QUESTION_DOT` handling to `ExpressionSemanticChecker.buildPostfix` returning `UnsupportedExpression`.
5. Extract `ExpressionTypeInferenceService` from `AffogatoTypeChecker` to reduce file size.
6. Make `activeTypeParams` a parameter of methods rather than mutable shared state.
7. Fix accessor generation to respect `FieldDecl.access` modifier.
8. Fix static field getter generation to be `static`.

---

## Feature × Test Matrix

| Feature | Implemented | Unit Test | Golden Test | Exec Test | Missing Cases | Risk |
|---|---|---|---|---|---|---|
| String interpolation — basic | ✅ | `AffogatoStringLiteralTokenTest` | `golden/interpolation` | `exec/string-interpolation` | `scanUnsupportedToken` nested quotes | MEDIUM |
| String interpolation — nested strings | ✅ | `AffogatoStringLiteralTokenTest` | — | `exec/interpolation-nested-*` | None known | LOW |
| String escapes | ✅ | — | `golden/string-escapes` | — | `\uXXXX` in nested context | MEDIUM |
| Property reads/writes | ✅ | — | `golden/property-*` | `exec/property-*` | Complex receiver double-eval | MEDIUM |
| Property mutation in expression | Diagnostic only | — | — | `negative/property-mutation-in-expression` | Prefix form, complex receiver | MEDIUM |
| Overload resolution | ✅ | — | `golden/overload-*` | `exec/named-args*` | Poly expressions, capture conversion | HIGH |
| Named arguments | ✅ | — | `golden/named-args*` | `exec/named-args*` | Named args + trailing closure | MEDIUM |
| `guard` statement | ✅ | — | `golden/guard*` | `exec/guard-skip` | Complex condition patterns | LOW |
| `switch` statement | ✅ | — | `golden/switch-*` | `exec/switch-statement` | Exhaustive switch, throw-arm | MEDIUM |
| `switch` expression | ✅ | — | `golden/switch-expression*` | `exec/switch-expression` | Block-arm rejection | LOW |
| `try/catch/finally` | ✅ | — | `golden/try-*` | `exec/try-catch`, `exec/finally-block`, `exec/multi-catch` | Multi-catch type compat | LOW |
| `for-in` loop | ✅ | — | `golden/for-in*` | `exec/for-in-loop` | C-style `for` loop | MEDIUM |
| `while` loop | ✅ | — | `golden/while*` | `exec/while-loop` | Infinite loop pattern | LOW |
| `break`/`continue` | ✅ | — | — | `exec/break-continue` | Labeled forms (confirm error) | LOW |
| Lambdas | ✅ | — | `golden/lambda*` | `exec/lambda-supplier` | Block body capturing outer scope | MEDIUM |
| Method references | ✅ | — | `golden/method-ref*` | — | Static method refs, constructor refs | MEDIUM |
| Extension functions | ✅ | — | `golden/extension*` | `exec/extension-call` | Unknown receiver type, name collision | MEDIUM |
| Records | ✅ | — | `golden/records*` | `exec/record-field` | Records with methods and constructors | LOW |
| Enums | ✅ (constants only) | — | `golden/enums*` | `exec/enum-*` | Enum with body methods | HIGH |
| Interfaces / default methods | ✅ | — | `golden/interface*` | — | Default method override conflict | MEDIUM |
| Annotations | ✅ | — | `golden/annotation*` | — | Annotation on lambda parameters | LOW |
| Generics | Partial | — | `golden/casts-generics`, `golden/collections-*` | — | Recursive bounds success case | HIGH |
| Nullability (`?` / `!`) | Partial | — | `golden/nullability*` | — | Non-null field without initializer | MEDIUM |
| `is` (instanceof) | ✅ | — | `golden/casts-instanceof*` | `exec/is-check` | Pattern variable binding edge cases | MEDIUM |
| `as` (cast) | ✅ | — | `golden/casts-*` | `exec/as-cast` | Cast to parameterized type erasure | LOW |
| Use-before-init | ✅ | `AffogatoUseBeforeInitTest` | — | `negative/use-before-init` | Use-before-init across `if` branches | MEDIUM |
| Unreachable code | ✅ | `AffogatoCompilerFlowTest` | — | `exec/unreachable-elided` | Unreachable after exhaustive switch | MEDIUM |
| Return flow analysis | Partial (location bug) | — | `negative/type-checker` | — | Method with throw-only body | MEDIUM |
| Safe call (`?.`) | Rejected (pre-scan) | — | — | — | Grammar/checker gap | HIGH |
| Elvis (`?:`) | Rejected (pre-scan) | — | — | — | `buildElvis` still builds AST node | HIGH |
| Abstract classes | Partial (no E2E test) | — | — | — | Missing end-to-end test | MEDIUM |
| Deep nesting / stack overflow | ✅ | `AffogatoRobustnessTest` | — | — | Deep interpolation DoS | LOW |
| CLI (`--help`, `--classpath`) | ✅ | — | — | — | `--fail-on-warnings` E2E | LOW |
| Gradle plugin | ✅ | — | Gradle TestKit | — | Multi-source-set incremental build | LOW |

---

## Hardening Plan

### Phase 1: Correctness (Immediate — 1–2 weeks)

1. **Fix `AFFOGATO_RETURN_FLOW` location** — Pass actual declaration line number in `AffogatoTypeChecker.typeCheckExecutable`. Remove unreachable dead-code duplicate checks from `AffogatoJavaGenerator.writeMethods` (lines 277, 593) and `generateExtensionsHolder`.
2. **Fix `validateTypeRef` hardcoded `1, 1`** — Replace all `validateTypeRef(type, unit, 1, 1)` calls in `typeCheckExecutable` and `typeCheckClass` with the actual parameter/field/return declaration line numbers.
3. **Fix `scanUnsupportedToken`** — Rewrite to use interpolation-aware nesting (matching `deepInterpolationIndex`), preventing false positives and missed detections when `?.`/`?:`/`!!` appear inside strings with nested quotes.
4. **Remove or fix `SafeCallExpression` / `ElvisExpression` rendering branches** — Simplest fix: call `diagnostics.add(UNSUPPORTED_*)` and return source verbatim. The diagnostic from typecheck will already abort compilation before generate in normal flow.
5. **Add `QUESTION_DOT` branch** in `ExpressionSemanticChecker.buildPostfix` returning `UnsupportedExpression`.
6. **Fix accessor modifier** — `writeAccessors` in `AffogatoJavaGenerator` should emit the accessor with the same visibility as the field, not always `public`. Private fields should not get public getters/setters by default.
7. **Fix static field getter** — `writeAccessors` emits non-static getters for `static` fields. Add `static` to the accessor when `field.isStatic()` is true.

### Phase 2: Tests (2–3 weeks)

1. Add unit test confirming `AFFOGATO_RETURN_FLOW` diagnostic `line()` matches the method declaration.
2. Add negative fixture for prefix property mutation in expression: `println(++c.n)`.
3. Add `scanUnsupportedToken`-specific unit test for `"${m["k"]}"` containing `?.` — confirm no false positive.
4. Add exec fixture for C-style `for` loop.
5. Add negative fixture for `func synchronized()` (reserved identifier on method name).
6. Add exec fixture for enum with body methods.
7. Add golden fixture for abstract classes.
8. Add regression test for static field getter being `static`.
9. Add test that two `.aff` files in the same compilation produce no duplicate `java.util.Objects` import lines.
10. Add exec fixture for throw-only method (no `AFFOGATO_RETURN_FLOW` expected).

### Phase 3: Diagnostics (1–2 weeks)

1. Propagate declaration source locations through `typeCheckExecutable` and `typeCheckClass` so all diagnostic line numbers are accurate.
2. Verify spell-check suggestion fires in all type-position contexts (return type, field type, parameter type).
3. Add a warning for switch statements/expressions without a `default` branch when the selector is a non-enum type.
4. Add `AFFOGATO_UNREACHABLE` for exhaustive switch statements on enum types when all constants are covered.
5. Improve `AFFOGATO_PROPERTY_MUTATION_EXPR` message to include the specific operator.

### Phase 4: Performance (2 weeks)

1. Add expression-AST caching keyed by `(methodContext.executableName, expression)` to avoid re-parsing the same expression substring during the generate pass.
2. Merge the three `scanUnsupportedToken` calls into a single source scan.
3. Add secondary index in `ClassSymbolTable` keyed by simple name, eliminating the linear FQN-scan fallback.
4. Profile `Gemma4.aff` compilation and confirm no O(n²) hotspot.

### Phase 5: Developer Experience (ongoing)

1. Extract `ExpressionTypeInferenceService` from `AffogatoTypeChecker` to bring that file below 1,000 lines.
2. Eliminate `setActiveTypeParams` mutable shared state — pass as a parameter to `typeCheck` and `expressionAst`.
3. Remove the `ExpressionRenderServices` backward-callback interface; have `ExpressionRenderer` accept type resolver and symbol resolver directly.
4. Upgrade `AffogatoLexerFixtureTest` to verify each fixture fails with a diagnostic pointing to the correct line.
5. Add a developer-facing `--verbose` CLI flag that logs compilation phase timings per file.

---

## Rock-Solid Checklist

- [ ] `AFFOGATO_RETURN_FLOW` diagnostic always points to actual method declaration line
- [ ] `validateTypeRef` calls never use hardcoded `1, 1` location in `typeCheckExecutable`
- [ ] `scanUnsupportedToken` correctly handles nested quotes inside string interpolation
- [ ] `ExpressionRenderer` does not emit undeclared / hash-named temp variables for SafeCall/Elvis
- [ ] `QUESTION_DOT` in `ExpressionSemanticChecker.buildPostfix` returns `UnsupportedExpression`
- [ ] Accessor generation respects field `access` modifier (private fields do not get public accessors)
- [ ] Static fields get `static` accessors
- [ ] `java.util.Objects` import is not duplicated when already in `unit.imports()`
- [ ] Extension function receiver parameter name `$this` is reserved and not user-declarable
- [ ] All 184 golden tests byte-identical after Phase 1 changes
- [ ] No diagnostic fires with `line=1, col=1` unless the error genuinely originates at the top of the file
- [ ] `exec/` fixtures cover enum with body methods, C-style for loop, static-field getter
- [ ] `negative/` fixtures cover all unsupported operator forms (including prefix `?.`, `?:`)
- [ ] `deepInterpolationIndex` and `scanUnsupportedToken` use consistent string-escaping logic
- [ ] `FlowAnalyzer.statementExits` has at least a stub for exhaustive switch
- [ ] `AffogatoRobustnessTest` passes under `-Xss512k` (minimal stack) confirming stack overflow is caught
- [ ] `Gemma4.aff` compiles in under 30 seconds (performance regression gate)
- [ ] `GrammarSyncTest` passes after any grammar change (enforced by CI)
- [ ] No ANTLR build warnings in `:affogato-compiler:compileJava` output
- [ ] `AffogatoCompiler.packageDirectory` path-traversal protection has a dedicated security test
- [ ] `URLClassLoader` in `JavaResolver` is closed after every compilation via try-with-resources (verified)
