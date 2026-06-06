# Affogato Audit Report

> Generated: 2026-06-05 (re-audit with live test run)  
> Auditor: Senior Compiler Engineer (AI)  
> Scope: Full project — lexer, parser, AST, type checker, codegen, tests, architecture, performance, diagnostics  
> Verification: `GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test`  
> - HEAD `eb312d3`: **7/61 FAILED** (48 golden + 7 exec regressions)  
> - Tras fix P0 (accessors + property-mutation guard): **6/61 FAILED** (28 golden + 3 exec)

---

## Resumen ejecutivo

- **Estado general:** Affogato es un transpiler source-to-source a Java 21 con arquitectura de pipeline clara (parse → register → typecheck → generate → write), gramática ANTLR sólida, harness de pruebas extenso (184 golden, 64 exec, 51 negative, 11 lexer, 12 parser) y diagnósticos de buena calidad. El proyecto está en **alfa avanzado** con uso real posible, pero **HEAD está roto**: la suite no pasa.
- **Riesgo principal:** Regresiones en el commit más reciente (`bug fixes`): (1) getters/setters generados con la visibilidad del campo (`private` en compact constructors) rompen el modelo de propiedades cross-class; (2) `AFFOGATO_PROPERTY_MUTATION_EXPR` se dispara en mutaciones válidas a nivel de statement (`c.n++`). Resultado: 48 golden mismatches + 7 exec failures + fallos en negative/lexer/self-test.
- **Prioridad más alta:** Restaurar verde la suite (fix accessors + fix property-mutation guard) antes de cualquier feature nueva.
- **Nivel de solidez actual:** 6 / 10 (bajaría a 5 si CI sigue rojo en main)
- **Nivel recomendado para rock-solid:** 9 / 10

---

## Hallazgos críticos

| Severidad | Área | Hallazgo | Impacto | Recomendación |
|---|---|---|---|---|
| **CRITICAL** | CI / Tests | `./gradlew :affogato-compiler:test` falla: 7/61 métodos JUnit. 48 golden mismatches, 7 exec failures, negative/lexer/self-test afectados. | CI rojo; confianza en releases = 0. | Bloquear merges hasta verde. Fix mínimo en `writeAccessors` + guard en `validateExpressionSemantics`. |
| **CRITICAL** | Codegen / Propiedades | `writeAccessors` emite getters/setters con `field.access()`. Compact constructor `var n: int` → campo `private` → `private int getN()`. Lecturas `o.inner.value` desde otra clase generan `o.getInner().getValue()` con métodos `private` → **javac falla**. | Java inválido en casos comunes de propiedades encadenadas. | **Campos** conservan visibilidad; **accessors siempre `public`** (modelo Affogato). Documentar en LANGUAGE_REFERENCE. |
| **CRITICAL** | Semántica | `AFFOGATO_PROPERTY_MUTATION_EXPR` se emite en `checkExpressionStatement` → `validateExpressionSemantics` para `c.n++` / `c.n += 1` a nivel statement. El codegen en `writeStatement` los soporta; el typechecker los rechaza. | Regresión: exec fixtures `property-inc-dec`, `property-compound-assign`, `chained-property-write` no compilan. | Solo rechazar mutación de propiedad cuando la expresión está **anidada** (no es la raíz del `expressionStatement`). |
| **HIGH** | Docs / Lexer | `LANGUAGE_REFERENCE.md` dice "Unicode identifiers are not supported yet", pero `AffogatoLexer.g4` acepta `JavaLetter: ~[\u0000-\u007F]`. Fixture `lexer/unicode-identifier` espera `AFFOGATO_PARSE` pero compila. | Semántica ambigua; tests contradictorios. | Decidir: rechazar en lexer (revertir fragment) o documentar soporte y actualizar negative → lexer-valid. |
| **HIGH** | Arquitectura | Doble vía de codegen: AST (`ExpressionSemanticChecker` + `ExpressionRenderer`) para validación; transformación string/regex en `AffogatoJavaGenerator.transformExpressionTypedInSpan` para emisión. | Bugs de desincronización; difícil extender. | Migración incremental documentada en README; priorizar AST para emisión. |
| **HIGH** | Flow analysis | `FlowAnalyzer.statementExits` no considera `switch`, `for-in`, `guard`. `return switch { ... }` exhaustivo no satisface `blockExits`. | Falsos `AFFOGATO_RETURN_FLOW` o falsos negativos. | Añadir ramas switch/for-in; exhaustividad enum en switch expression. |
| **MEDIUM** | Features incompletas | Enums: solo constantes (`ParsedEnum` sin métodos). `throws` ausente en gramática. C-style `for (expr; expr; expr)` parseable (`forContent: expression`) sin codegen dedicado. | Gaps de interop Java y DSL incompleto. | Documentar como out-of-scope o implementar con fixtures. |
| **MEDIUM** | Diagnósticos | `validateTypeRef(parameter.type(), unit, 1, 1)` en `writeCompactConstructor` (línea ~458). Parámetros de compact constructor reportan `1:1`. | Ubicación incorrecta en errores de tipo. | Pasar `parameter` declaration line/col. |
| **MEDIUM** | Docs obsoletos | `AFFOGATO_ROADMAP.md` afirma "build is green" (2026-06-04); HEAD no compila tests. | Contribuidores confiados en estado incorrecto. | Actualizar roadmap tras fix. |
| **LOW** | Performance | `antlrParseCache` amortiza re-parse ANTLR entre typecheck/generate, pero `buildExpression` se re-ejecuta por contexto. Sin benchmarks ni compilación incremental. | Lentitud en archivos grandes (`Gemma4.aff`). | Benchmark gate; cache de `AstExpression` tipado. |

### Issues corregidos desde auditoría previa (verificados en código)

| Issue previo | Estado actual |
|---|---|
| `AFFOGATO_RETURN_FLOW` en `1:1` | **Corregido** — usa `declarationLine` en `typeCheckExecutable` |
| `scanUnsupportedToken` naive | **Corregido** — `scanUnsupportedSourceEdges` interpolation-aware |
| `SafeCallExpression` / `ElvisExpression` codegen inválido | **Corregido** — `ExpressionRenderer` retorna `ast.source()` |
| `QUESTION_DOT` en `buildPostfix` | **Corregido** — retorna `UnsupportedExpression` |
| Duplicado `AFFOGATO_RETURN_FLOW` en generator | **Corregido** — no hay matches en `AffogatoJavaGenerator` |
| Double-eval en compound assign (complex receiver) | **Parcialmente corregido** — hoisting en `ExpressionRenderer` y `transformPropertyCompoundAssignment` |

---

## Casos no testeados

| Área | Feature | Caso faltante | Riesgo | Test recomendado |
|---|---|---|---|---|
| Typecheck | Property mutation statement vs expr | `c.n++` como statement NO debe emitir `AFFOGATO_PROPERTY_MUTATION_EXPR` | CRITICAL | Unit test en `AffogatoTypeChecker` + restaurar exec fixtures |
| Codegen | Accessor visibility | `class C(var x: int)` desde otra clase: getter debe ser `public` | CRITICAL | Golden `block-expression-statements` + exec `chained-property` |
| Flow | Return via switch expression | `run(): int { return switch (x) { case 1 -> 2; default -> 3 } }` | MEDIUM | Golden fixture |
| Flow | Throw-only method | `run(): int { throw RuntimeException() }` sin `AFFOGATO_RETURN_FLOW` | MEDIUM | Negative + golden |
| For | C-style 3-part for | `for (var i = 0; i < 10; i++) { }` — parsea pero ¿codegen? | MEDIUM | Exec o negative explícito |
| Enum | Body methods | `enum E { A, B; func label(): String { return "x" } }` | HIGH | Golden + exec |
| Generics | Recursive bound success | `<T: Comparable<T>>` resolución positiva | HIGH | Golden (negative existe) |
| Nullability | Non-null field sin init | `let name: String!` sin inicializador | MEDIUM | Negative fixture |
| Interop | `throws` clause | Override de método Java con checked exceptions | HIGH | Golden con `throws` |
| Security | Path traversal | `packageDirectory` con `..` en segmento | LOW | Unit test dedicado (lógica existe, sin test) |
| Unicode | Identificadores | `let café = 1` — política explícita | MEDIUM | Decisión docs + fixture coherente |
| Prefix property mutation | `println(++c.n)` en expresión | Solo postfix en negative | MEDIUM | Ampliar `negative/property-mutation-in-expression` |
| Diagnostics | `AFFOGATO_RETURN_FLOW` location | Assert `diagnostic.line()` == method decl | LOW | Unit test (ya corregido en código) |

---

## Casos no implementados o incompletos

| Feature | Estado actual | Evidencia | Recomendación |
|---|---|---|---|
| Safe call (`?.`) / Elvis (`?:`) / `!!` | Rechazados pre-scan + `UnsupportedExpression` en AST | `scanUnsupportedSourceEdges`, `buildPostfix` | OK para production subset; mantener negative fixtures |
| Enum con métodos | Solo constantes | `generateEnum` emite `A, B` sin cuerpo | Añadir `List<MethodDecl>` a `ParsedEnum` |
| `throws` en métodos | No en gramática | Sin `THROWS` en lexer | Gap interop Java; roadmap |
| C-style `for` | Parseable (`forContent: expression`) | `AffogatoParser.g4:334-337` | Rechazar con diagnóstico claro o implementar |
| Do-while / labeled break-continue | No en gramática | `negative/labeled-break-continue` espera `AFFOGATO_PARSE` | Documentar out-of-scope |
| Switch exhaustiveness (flow) | No implementado | `FlowAnalyzer` sin rama switch | Implementar para enums |
| Safe call en interpolación | Pre-scan + AST guard | Tests exec `interpolation-nested-string` | OK |
| Text blocks `"""` | No | Sin regla lexer | Roadmap |
| Pattern matching / sealed | No | Sin keywords | Roadmap |
| Compilación incremental | No | Full recompile por archivo | Gradle plugin podría cachear por input hash |
| Bytecode backend | No | Solo `.java` | Documentado |

---

## Problemas de generación Java

| Caso | Java generado | Problema | Solución |
|---|---|---|---|
| Compact constructor `var n` | `private int getN()` | Getter private; `other.getN()` falla javac | Accessors siempre `public`; campo mantiene `private` |
| `o.inner.value` cross-class | `o.getInner().getValue()` con getters private | No compila | Idem |
| `c.n++` statement | N/A (no llega a codegen) | Typechecker rechaza antes | Guard en validación semántica |
| Compound assign complex receiver | `var $affogato$recv$ = make(); recv.setN(...)` | **Corregido** en statement path | Verificar en expression path (renderer) |
| `SafeCallExpression` si escapa validación | `ast.source()` verbatim | Java inválido pero diagnóstico previo aborta | OK como fallback defensivo |
| `java.util.Objects` import | Inyectado si `requiresRuntimeCheck()` | Posible duplicado si ya importado | Chequear `unit.imports()` antes de add |
| Extension `$this` param | Reservado en parser | Usuario no puede nombrar param `$this` | OK — validado en `AffogatoParserRunner` |

---

## Problemas semánticos

| Caso | Comportamiento actual | Comportamiento esperado | Recomendación |
|---|---|---|---|
| `c.n++` en statement | `AFFOGATO_PROPERTY_MUTATION_EXPR` | Permitir; rechazar solo en expresión anidada | Flag `inStatementPosition` o skip en `checkExpressionStatement` |
| Visibilidad accessors | Copia `field.access()` | Campo private, accessor public para propiedades Affogato | Separar visibilidad campo vs accessor |
| Unicode `café` | Compila | Docs dicen "not supported" | Alinear docs y fixtures |
| `a == b == c` | Left-associative `(a==b)==c` | Documentado en LANGUAGE_REFERENCE | OK; test `AffogatoChainedEqualityDiagnosticTest` |
| `for-in` variable después del loop | `restoreScope` restaura `mutableVariables` | Variable no visible después | **Corregido** en `MethodContext.restoreScope` |
| Enum constant anidado `Outer.Inner.CONST` | Puede devolver `unknown` en edge cases | Resolver qualifier | Mejorar `enumConstantAccessType` |
| C-style for | Parsea como `for (expression) block` | Error claro o soporte Java | Negative fixture o implementación |
| Unused locals | Sin warning | Warning opcional `AFFOGATO_UNUSED_LOCAL` | Fase diagnostics |

---

## Performance

| Área | Problema | Impacto | Mejora recomendada |
|---|---|---|---|
| Expression parsing | `parseViaAntlr` + `buildExpression` por expresión; cache de parse tree parcial | O(expresiones × tamaño) en archivos grandes | Cache `AstExpression` tipado post-typecheck |
| `deepInterpolationIndex` + `scanUnsupportedSourceEdges` | 2 scans O(n) pre-parse | Overhead constante por archivo | Aceptable; merge opcional |
| `ClassSymbolTable.lookup` fallback | Scan lineal por simple name | O(tipos) en proyectos multi-archivo | Índice secundario por simple name |
| `JavaResolver.loadClass` | ClassLoader en classpath grande | Latencia en primera compilación | Metadata cache (`javaMetadataCacheDirectory`) — ya existe |
| String transforms | Regex en `transformExpressionTypedInSpan` | CPU en expresiones complejas | Migrar a AST renderer |
| Sin benchmarks | No hay JMH ni gate de tiempo | Regresiones silenciosas | `Gemma4.aff` < 30s como gate CI |

---

## Diagnósticos

| Error actual | Problema | Error recomendado |
|---|---|---|
| `AFFOGATO_PROPERTY_MUTATION_EXPR` en `c.n++` statement | Falso positivo | No emitir en raíz de `expressionStatement` |
| `AFFOGATO_PARSE` en `1:1` para stack overflow | Sin ubicación precisa | Mejor token del stream ANTLR si disponible |
| `validateTypeRef` en compact constructor `1:1` | Columna/línea incorrecta | Línea del parámetro |
| Diagnósticos en general | Snippet + caret + hint vía `AffogatoDiagnosticRenderer` | **Buen estado** — modelo a seguir |
| Códigos estables | `AffogatoDiagnosticCodes` con hints | **Buen estado** — 40+ códigos documentados |

---

## Arquitectura

**Lo que está bien:**
- Pipeline de 4 fases con `failIfNeeded` — sin escritura parcial en error (`AffogatoCompiler`)
- `AffogatoTranspiler` facade delgado (57 líneas) → `CompilationSession`, `AffogatoTypeChecker`, `AffogatoJavaGenerator`
- `AstExpression` sealed hierarchy (Java 21) — extensible y type-safe
- `CompilationSession` + `AutoCloseable` cierra `URLClassLoader`
- Harness de fixtures: golden (byte-exact + javac), exec (E2E), negative (códigos), lexer/parser
- `GrammarSyncTest` — sync ANTLR ↔ IntelliJ JFlex/BNF
- Path traversal protection en `packageDirectory` (normalize + startsWith)
- `antlrParseCache` compartido entre passes
- Fuzz test (`AffogatoLexerParserFuzzTest`) — no cuelgue en input aleatorio
- Robustness: deep nesting → `AFFOGATO_PARSE`, no crash JVM

**Lo que está débil:**
- `AffogatoJavaGenerator` (2546 líneas) + `AffogatoTypeChecker` (2579 líneas) — monolitos
- `ExpressionRenderServices` — callback bidireccional generator ↔ renderer
- Dual-track codegen (regex + AST) — fuente histórica de fragilidad
- Dos gramáticas (ANTLR + Grammar-Kit) — sync manual, riesgo de divergencia IDE vs compiler
- Sin IR estable entre fases más allá del parse tree ANTLR
- `setActiveTypeParams` mutable shared state
- Sin compilación incremental en compiler (Gradle recompila todo el source set)

**Cambios recomendados (orden):**
1. Fix regressions (accessors + property mutation guard) — **inmediato**
2. Completar migración AST → codegen (retirar regex path gradualmente)
3. Extraer `OverloadResolver`, `TypeInferenceService` de monolitos
4. Eliminar `setActiveTypeParams` — pasar como parámetro
5. FlowAnalyzer: switch + for-in + guard exhaustiveness

---

## Clasificación de pruebas existentes

| Tipo | Clase(s) | Fixtures / alcance |
|---|---|---|
| Lexer tests | `AffogatoLexerFixtureTest`, `AffogatoLexerValidFixtureTest`, `AffogatoStringLiteralTokenTest` | 11 lexer + 4 lexer-valid |
| Parser tests | `AffogatoParserFixtureTest`, `AffogatoParserValidFixtureTest` | 12 parser + parser-valid |
| AST tests | `ExpressionSemanticCheckerTest` | Unitarios parciales |
| Semantic tests | `AffogatoUseBeforeInitTest`, `AffogatoChainedEqualityDiagnosticTest`, `AffogatoDiagnosticSuggestionTest`, `AffogatoCompilerFlowTest` | Unitarios |
| Codegen tests | `AffogatoGoldenTest` | 184 golden (byte-exact Java) |
| Java compilation tests | Golden + Exec (javac `--release 21`) | 184 + 64 |
| Runtime behavior | `AffogatoExecutionTest` | 64 exec (stdout/stderr/exit) |
| Golden file tests | `AffogatoGoldenTest` | Sí |
| Regression tests | Negative fixtures | 51 casos |
| Error message tests | Negative + `AffogatoDiagnosticSuggestionTest`, `AffogatoDiagnosticRendererTest` | Parcial |
| E2E tests | Exec + `AffogatoClasspathIntegrationTest` | Sí |
| Fuzz tests | `AffogatoLexerParserFuzzTest` | 250 iteraciones |
| Grammar sync | `GrammarSyncTest` | Keywords/operators |
| Robustness | `AffogatoRobustnessTest` | Deep nest, main signature |
| IO / Security | `AffogatoCompilerIOTest` | IO, write, bad package |
| Integration | `AffogatoClasspathIntegrationTest`, `AffogatoGradlePluginTest` | Classpath, Gradle |
| IntelliJ | `AffogatoReferenceTest` | Referencias PSI |
| Self-test monolítico | `AffogatoCompilerSelfTest` | ~1500 líneas inline |

**E2E pipeline verificado por harness:**
```
.aff → ANTLR parse → symbols → typecheck → Java codegen → write → javac → run() → stdout
```
Existe (`AffogatoExecutionTest`). **Actualmente 7/64 fixtures fallan en HEAD.**

---

## Matriz Feature × Tests

| Feature | Implementada | Unit | Golden | Exec | Negative | Casos faltantes | Riesgo |
|---|---:|---:|---:|---:|---:|---|---|
| Clases / campos / métodos | ✅ | Parcial | ✅ | ✅ | ✅ | Abstract E2E limitado | OK |
| Compact constructor + props | ✅ | — | ✅ | ✅ | ✅ | Accessor visibility regression | **CRÍTICO** |
| Records | ✅ | — | ✅ | ✅ | ✅ | — | OK |
| Enums (solo constantes) | Parcial | — | ✅ | ✅ | ✅ | Métodos en enum body | HIGH |
| Interfaces + default | ✅ | — | ✅ | — | — | Override conflict | MEDIUM |
| String interpolation | ✅ | ✅ | ✅ | ✅ | ✅ | — | OK (nested string fixed) |
| String escapes / `\u` | ✅ | — | ✅ | — | ✅ | — | OK |
| Operadores / precedencia | ✅ | ✅ | ✅ | ✅ | ✅ | `a==b==c` | OK |
| `guard` | ✅ | — | ✅ | ✅ | ✅ | — | OK |
| `for-in` | ✅ | — | ✅ | ✅ | — | Scope post-loop | OK |
| C-style `for` | Ambigua | — | — | — | — | Parse sin semántica clara | MEDIUM |
| `while` / break / continue | ✅ | — | ✅ | ✅ | — | Labeled (rechazado) | LOW |
| `switch` stmt/expr | ✅ | — | ✅ | ✅ | ✅ | Flow exhaustiveness | MEDIUM |
| `try/catch/finally` | ✅ | — | ✅ | ✅ | — | — | OK |
| Lambdas / method refs | ✅ | — | ✅ | ✅ | ✅ | Capture scope edge | MEDIUM |
| Extension functions | ✅ | — | ✅ | ✅ | ✅ | Unknown receiver | MEDIUM |
| Named args / overloads | ✅ | — | ✅ | ✅ | ✅ | JLS poly/capture | HIGH |
| Nullability `?`/`!` | Parcial | — | ✅ | — | ✅ | Field sin init `!` | MEDIUM |
| `is` / `as` | ✅ | — | ✅ | ✅ | — | Pattern vars | MEDIUM |
| Property read/write | ✅ | — | ✅ | ✅ | — | Private getter regression | **CRÍTICO** |
| Property mutation stmt | ✅ | — | — | ✅ | — | Typecheck regression | **CRÍTICO** |
| Property mutation expr | Diagnostic | — | — | — | ✅ | Prefix `++c.n` | MEDIUM |
| Java interop | ✅ | — | ✅ | ✅ | ✅ | `throws`, capture | HIGH |
| Generics | Parcial | — | ✅ | — | ✅ | Recursive bound + | HIGH |
| Annotations | ✅ | — | ✅ | — | — | Lambda params | LOW |
| `?.` / `?:` / `!!` | Rechazado | — | — | — | ✅ | — | OK |
| Unicode identifiers | Ambigua | — | — | — | ⚠️ | Docs vs lexer | MEDIUM |
| Multi-file / packages | ✅ | — | ✅ | ✅ | ✅ | — | OK |
| Main entry | ✅ | ✅ | — | — | — | — | OK |
| Deep nesting DoS | ✅ | ✅ | — | — | ✅ | — | OK |
| Diagnostics quality | ✅ | ✅ | — | — | ✅ | Compact ctor `1:1` | MEDIUM |
| Gradle plugin | ✅ | — | — | — | — | Incremental | LOW |
| IntelliJ plugin | Parcial | ✅ | — | — | — | Completion/formatter | LOW |

---

## Plan de endurecimiento

### Fase 1: Correctness (inmediato — días)

1. **Restaurar CI verde:** accessors `public` + guard property-mutation en statements.
2. Actualizar/verificar 48 goldens si el fix de accessors cambia output (debería restaurar expected).
3. Decisión Unicode: rechazar o documentar soporte.
4. Fix `validateTypeRef(..., 1, 1)` en `writeCompactConstructor`.

### Fase 2: Tests (1–2 semanas)

1. Unit test: `AFFOGATO_RETURN_FLOW` apunta a línea del método.
2. Unit test: `packageDirectory` rechaza `..` traversal.
3. Golden: enum con métodos (cuando implementado).
4. Exec/negative: C-style for (rechazo o soporte).
5. Negative: `let x: String!` sin init.
6. Ampliar `property-mutation-in-expression` con prefix `++c.n`.

### Fase 3: Diagnostics (1–2 semanas)

1. Propagar ubicaciones en todos los `validateTypeRef`.
2. `AFFOGATO_PROPERTY_MUTATION_EXPR` incluir operador específico.
3. Warning switch sin `default` en tipos no-enum.
4. `AFFOGATO_UNUSED_LOCAL` opcional.

### Fase 4: Performance (2 semanas)

1. Cache `AstExpression` tipado entre typecheck y generate.
2. Benchmark gate con `Gemma4.aff`.
3. Índice simple-name en `ClassSymbolTable`.

### Fase 5: Developer Experience (ongoing)

1. Extraer servicios de `AffogatoTypeChecker` / `AffogatoJavaGenerator`.
2. Completar migración AST codegen.
3. IntelliJ: completion, formatter, quick fixes.
4. `--verbose` con timings por fase.

---

## Checklist rock-solid

- [x] Lexer robusto (interpolation depth limit, escapes, numeric validation)
- [x] Parser robusto (stack overflow → diagnóstico)
- [x] AST consistente (sealed `AstExpression`)
- [ ] Semantic analyzer completo (property mutation guard, flow switch)
- [ ] Codegen Java 21 confiable (**regresión accessors**)
- [x] Tests unitarios base (24 clases JUnit)
- [ ] Tests E2E con compilación real (**7/64 fallan**)
- [ ] Golden tests de Java generado (**48/184 fallan**)
- [x] Error messages claros (renderer + hints)
- [ ] Benchmarks básicos
- [x] Documentación de sintaxis (`LANGUAGE_REFERENCE.md`)
- [ ] Documentación de semántica alineada (Unicode, accessors, C-for)
- [ ] CI ejecutando tests completos (**ROJO**)
- [ ] CI compilando Java generado
- [x] Casos inválidos cubiertos (51 negative)
- [x] Casos edge cubiertos (robustness, fuzz, deep interpolation)

---

## Bugs/riesgos priorizados

| # | Prioridad | Bug/Riesgo | Acción |
|---|---|---|---|
| 1 | P0 | CI rojo — 28 golden + 3 exec + unicode/negative | Ver fixes aplicados abajo + property chain types |
| 2 | P0 | Getters private rompen propiedades cross-class | **FIX APLICADO** — accessors siempre `public` |
| 3 | P0 | `AFFOGATO_PROPERTY_MUTATION_EXPR` en statements | **FIX APLICADO** — `allowTopLevelPropertyMutation` |
| 3b | P0 | `o.inner.value` → `o.getInner().value` (sin getter 2º hop) | `PropertyAccessExpression` siempre `TypeGuess.unknown()` en AST |
| 4 | P1 | Unicode: docs vs lexer vs fixtures | Decisión explícita + actualizar tests/docs |
| 5 | P1 | Dual codegen (regex + AST) | Migración incremental a AST |
| 6 | P1 | FlowAnalyzer sin switch/for-in | Ampliar `statementExits` |
| 7 | P2 | Enum methods no implementados | Extender `ParsedEnum` + codegen |
| 8 | P2 | `throws` ausente | Gramática + codegen |
| 9 | P2 | C-style for ambiguo | Rechazar o implementar |
| 10 | P3 | Monolitos 2500+ líneas | Extracción gradual |
| 11 | P3 | Sin benchmarks | JMH o gate temporal |
| 12 | P3 | ROADMAP desactualizado | Actualizar tras P0 |

---

## Constructos del lenguaje (inventario)

**Soportados con tests razonables:** packages, imports (static), classes (inheritance `: Parent, Iface`), compact/explicit constructors, records, enums (constants), interfaces (default methods), fields (`var`/`let`), methods (`func` = void), locals, control flow (`if/else`, `guard`, `for-in`, `while`, `switch` stmt/expr, `try/catch/finally`, `return`, `throw`, `break`, `continue`), expressions (literales, calls, props, ops, casts, ternaries, lambdas, method refs, arrays), string interpolation, extension functions, annotations, nullability markers, Java interop, named args, trailing closures, `[T]` list shorthand.

**Incompletos / ambiguos:** enum methods, `throws`, C-style for, Unicode policy, safe-call family (rechazado), abstract classes (golden sí, cobertura limitada), generics JLS corners, pattern matching, text blocks, incremental compile.

**Sin tests dedicados:** do-while (no existe), labeled break (negative sí), `throws`, enum methods, unused variable warning, path traversal security unit test.
