# Agent Task — Close the Remaining Fragility Cases in the Affogato Transpiler

You are a **senior compiler / programming-languages / Java 21 engineer**. You are working on
**Affogato**, a language that transpiles **source-to-source to Java 21**. The compiler lives in
`affogato-compiler/`. Work **test-first**, make **minimal, surgical** changes, and **do not break a
single golden test**.

This task has **two independent bugs**. Do them in **separate commits**. Bug B is the hard one and
this spec mandates the **ANTLR lexer-modes** approach (full re-tokenization), not a shortcut.

> Already fixed in a prior effort — **do not touch / do not redo**: chained casts (`o as X as Y`),
> chained property reads (`a.b.c`), property reads on call/cast results (`make().name`), object/array
> literal inference and target-typing, multidimensional array literals, property *statement*-position
> compound-assign and increment/decrement (`c.n += x`, `c.n++`), and writes through complex receivers
> (`a.b.c = x`, `make().z = 5`). Those all work and are covered by exec fixtures.

---

## Architecture you must understand first

Read `affogato-compiler/src/main/java/dev/affogato/compiler/internal/AffogatoTranspiler.java`
(~4900 lines). Key facts:

- **Statements** are lowered from the ANTLR parse tree (`writeStatement`, `writeIf`, `writeFor`, …).
- **Expressions** are transpiled by a **chain of string/regex rewrites** in
  `transformExpressionTypedInSpan(...)` — interpolation, casts, named args, property reads, etc. This
  string-rewriting pipeline is the project's historical source of fragility. Both bugs below live in
  or around it.
- A real expression AST exists (`ExpressionAst.java`), built by `ExpressionSemanticChecker.parse()`
  (it re-parses each expression substring with ANTLR). It is used for **validation only**; codegen
  ignores it.
- Lexer/parser grammar (compiler): `affogato-compiler/src/main/antlr/dev/affogato/compiler/parser/Affogato.g4`.
  Building runs ANTLR's `generateGrammarSource`, producing `AffogatoLexer` / `AffogatoParser` under
  `affogato-compiler/build/generated-src/antlr/main/...`.
- A **second, separate** grammar drives the IntelliJ plugin:
  `affogato-intellij-plugin/src/main/grammar/Affogato.bnf` (+ `Affogato.flex`). Per the repo's
  `CLAUDE.md`, **syntax changes must be reflected in both grammars**. The BNF uses a deliberately flat
  `expression ::= lambdaExpression | expressionAtom+` model and is usually permissive, but for a
  string-tokenization change you must update `Affogato.flex` (the JFlex lexer) too, or at minimum
  confirm the plugin still builds.

### Commands

```bash
# Full compiler suite (184 golden + exec + negative + parser + lexer fixtures)
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test

# Build a runnable CLI for manual probing
GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:installDist
BIN=affogato-compiler/build/install/affogato-compiler/bin/affogato-compiler
# usage: $BIN <source-dir> <generated-java-out-dir>

# Verify generated Java really compiles:
( cd <out-dir> && javac *.java )

# Only when an output change is INTENTIONAL, refresh expected files:
#   -Daffogato.golden.update=true     (golden tests)
#   -Daffogato.exec.update=true       (exec stdout)
```

### Test harnesses

- **Exec** (compiles Affogato → `javac` → runs → compares stdout): create
  `affogato-compiler/src/test/resources/exec/<name>/` with `<Class>.aff`, `main-class.txt` (FQN),
  `entry-point.txt` (`run` or `main`), `expected-output.txt`. The class must expose
  `static func run()` → `public static void run()`.
- **Negative** (expects compile failure with given diagnostic codes): create
  `affogato-compiler/src/test/resources/negative/<name>/` with `<Class>.aff` and
  `expected-diagnostics.txt` (one code per line; matched as a **subset**; the fixture must leave **no**
  generated Java).
- **Golden** (byte-exact generated Java): `affogato-compiler/src/test/resources/golden/<name>/`.

### Non-negotiable constraints

- Target **Java 21**. The generated Java must compile with `javac --release 21`.
- The **184 golden tests must stay byte-identical**. If a change alters one *intentionally*, update it
  with the flag and justify it in the commit message; otherwise it's a regression.
- For each bug: (1) write the failing regression test, (2) **confirm it fails**, (3) apply the minimal
  fix, (4) confirm it passes, (5) run the **full** suite and review every diff.

---

## BUG A — Property mutation in EXPRESSION position (Group 2)

### Reproduce
```aff
class Counter(var n: int) { }
class A {
  static func run() {
    let c = Counter(0)
    println(c.n++)          // emits: System.out.println(c.getN()++);  -> javac ERROR "unexpected type"
  }
}
```
Also broken: `let x = c.n++`, `f(c.n += 2)`, `arr[c.n++]`, prefix `println(++c.n)`.

### Root cause
The property-mutation handlers (`transformPropertyIncDec`, `transformPropertyCompoundAssignment`,
`transformComplexPropertyWrite`) only run at **statement** position (the `expressionStatement` branch of
`writeStatement`). When `c.n++` / `c.n += x` appears **inside a larger expression**, it flows through
`transformPropertyReads`, which lowers `c.n` → `c.getN()` and leaves the trailing `++` / `+=`, producing
invalid Java like `c.getN()++`.

### Why it's genuinely hard
Java has no clean *expression* equivalent: a setter returns `void`, and you cannot embed a statement
(needed for a temp) inside a Java expression. So `c.n++` *as a value* (which must yield the old/new
value) cannot be desugared inline without a lambda/IIFE or runtime helper.

### Required approach
- **Primary (do this): emit a clear diagnostic instead of invalid Java.** Detect a getter/setter-backed
  property `++`/`--`/compound that is **not** in statement position and raise a new stable code, e.g.
  `AFFOGATO_PROPERTY_MUTATION_EXPR`, with message + hint like: *"Mutating property `c.n` with
  `++`/`--`/`+=` is not supported inside an expression; do it in a separate statement."* This **prevents
  invalid Java** and matches the project's `correctness > diagnostics > features` priority.
  - Best detection point: when a property read has just been lowered to a getter and is immediately
    followed by `++`/`--`, or preceded by `++`/`--`, or is the target of an `op=` inside an expression.
    Look at `transformPropertyReads` / `lowerBufferReceiverProperty` / `lowerPropertyChain`. You need the
    *receiver type* to know the member is getter/setter-backed (so you don't flag a public mutable Java
    field, which is valid as `obj.field++`).
  - Register the code in `AffogatoDiagnosticCodes` (hint map) and add it to the catalog table in
    `docs/LANGUAGE_REFERENCE.md`.
  - Negative fixture: `negative/property-mutation-in-expression/`.
- **Stretch (optional, only if you want full support): inline desugaring.** Desugar to a typed
  lambda/IIFE or a small `affogato-runtime` helper — e.g. postfix `c.n++` (int) →
  `<helper>.postInc(c::getN, c::setN)`. This is **type-specific** (int/long/double/short/byte/char) and
  must preserve prefix vs. postfix value semantics. Only attempt after the diagnostic path is solid, and
  add exec fixtures proving the returned value is correct.

### Must NOT regress
Local-variable and array-element mutation in expressions stay native and valid: `println(i++)`,
`arr[i]++`, `m[k] += 1`. Add a guard test for these.

---

## BUG B — String literal inside `${...}` interpolation (Group 4b) — **lexer modes**

### Reproduce
```aff
class A {
  greet(s: String): String { return s }
  static func run() {
    println("${greet("x")}")     // AFFOGATO_PARSE: mismatched input 'x' ...
  }
}
```

### Root cause
In `Affogato.g4` a string is a **single token**:
```antlr
StringLiteral : '"' StringCharacter* '"' ;
fragment StringCharacter : ~["\\\r\n] | EscapeSequence ;
```
`~["\\\r\n]` excludes `"`, so the inner `"` of `${greet("x")}` **terminates the string**. The transpiler
treats `"..."` as one token and `transformStringInterpolation(String, MethodContext)` (in
`AffogatoTranspiler`, ~line 2619) re-parses that text itself (it already has
`findMatchingBraceSkippingStrings`). The lexer never hands it the full string.

### Mandated approach: ANTLR **lexer modes** (full re-tokenization)

A recursive single-token rule is explicitly **out of scope** here — implement proper lexer modes so the
interpolated string is a real, structured construct. This is a deep change touching the **lexer, the
parser grammar, and the interpolation codegen**. Budget for it and test exhaustively.

#### 1. Lexer (`Affogato.g4`)

Split string lexing into a `STRING` mode and an interpolation that pushes back to a normal mode, using a
brace counter so a `}` that closes a nested block (e.g. a lambda inside `${ }`) does not prematurely end
the interpolation. Sketch (adapt names/escapes to the existing grammar, which supports `\t \n \b \r \f
\" \' \\` and `\uXXXX`):

```antlr
// ----- default mode -----
DQUOTE : '"' -> pushMode(STR) ;

mode STR;
STR_END    : '"' -> popMode ;
STR_ESCAPE : '\\' ([btnfr"'\\] | 'u'+ HexDigit HexDigit HexDigit HexDigit) ;
STR_INTERP_OPEN : '${' -> pushMode(INTERP) ;   // bump interpDepth in members
STR_DOLLAR : '$' ;                              // a '$' not followed by '{' is literal text
STR_TEXT   : ~["\\$]+ ;                         // run of plain text

mode INTERP;
// Inside ${ ... } we lex *normal* tokens, including nested strings (which re-enter STR
// via DQUOTE) and nested ${...}. The matching '}' pops back to STR.
INTERP_DQUOTE : '"' -> pushMode(STR) ;
INTERP_LBRACE : '{' -> pushMode(INTERP) ;       // nested block (lambda body): track depth
INTERP_CLOSE  : '}' -> popMode ;                // closes this ${ } or nested block
// ... re-declare / share the rest of the normal token set for INTERP, or use a single shared
//     mode and a `@members` brace counter. Easiest robust pattern: an `int interpBrace` stack
//     where '{' increments and '}' either decrements (nested block) or, at 0, popMode + emit
//     the interpolation-close token.
```

The cleanest standard implementation is a **brace-depth counter in `@lexer::members`**: on `${` push a
new depth=0; on `{` increment; on `}` either decrement (still inside a nested block) or, when depth hits
the boundary, emit the close token and `popMode`. The existing grammar already has an `openBrackets`
deque in `@lexer::members` for newline suppression — extend that mechanism; keep newline handling
consistent (newlines inside `${...}` should behave like inside `(`/`[`).

#### 2. Parser (`Affogato.g4`)

A string is no longer one token. Add a parser rule that assembles the parts, and replace
`StringLiteral` in the `literal` rule:

```antlr
literal
    : interpolatedString
    | IntegerLiteral
    | FloatingPointLiteral
    | TRUE | FALSE | NULL
    ;

interpolatedString
    : DQUOTE stringPart* STR_END ;

stringPart
    : STR_TEXT
    | STR_ESCAPE
    | STR_DOLLAR
    | STR_INTERP_OPEN expression INTERP_CLOSE     // ${ <real Affogato expression> }
    ;
```
Now the embedded `${ ... }` expression is a **real parse-tree `expression` node** (with correct nesting,
nested strings, lambdas, etc.) — not text scraped by regex.

#### 3. Codegen

- Add a method that lowers an `interpolatedString` **parse-tree node** to Java string concatenation:
  iterate `stringPart`s, accumulate literal text/escapes into a `"..."` segment, and for each
  `${ expression }` emit `+ (` + `transformExpression(sourceText(expression), context)` + `)`. Reuse the
  existing rendering rules in `transformStringInterpolation` (leading `"" +` when the first part is an
  expression, `\$` → literal `$`, empty `${}` → `AFFOGATO_PARSE`).
- Decide how this interacts with the legacy text-based `transformStringInterpolation`: ideally the new
  parse-tree lowering **replaces** it for `literal` strings, and the old method is retired or kept only
  for any residual text path. Make sure the **pipeline order** in `transformExpressionTypedInSpan` still
  produces identical output for every existing string golden.

#### 4. Token-iterating code that assumes single-token strings — fix all of it

These currently iterate tokens of type `AffogatoLexer.StringLiteral` and **will break**:
- `AffogatoTranspiler.validateNumericLiterals(...)` loop and `validateStringEscapes(Path, Token)` —
  escape validation (rejecting `\u` that decodes to `"`/`\`/CR/LF) must be re-expressed over the new
  `STR_ESCAPE` tokens (the per-token escape text is now smaller and cleaner — likely *easier*).
- `scanUnsupportedSourceEdges` / `scanUnsupportedToken` (raw-source scans for `?.`, `?:`, `!!` that skip
  string contents) — re-validate that "inside a string" detection still holds with the new tokenization;
  these scan raw text independently of the lexer, so they may be fine, but confirm with tests.
- Anywhere else that references `AffogatoLexer.StringLiteral` or `StringLiteralContext` — grep and fix.

#### 5. IntelliJ grammar

Update `affogato-intellij-plugin/src/main/grammar/Affogato.flex` (and `Affogato.bnf` if needed) so the
plugin lexer doesn't choke on quotes inside `${...}`, OR confirm its existing flat/loose handling still
builds. At minimum, `./gradlew :affogato-intellij-plugin:compileJava` (or the generate tasks) must pass.

### Risk & testing (this is the dangerous part)

There are **many** string and interpolation goldens (`golden/interpolation*`, `golden/string-*`,
`exec/string-interpolation`, `exec/interpolation-edges`, `golden/string-escapes`, …). A tokenization
change can perturb all of them. **Run the full suite and review every diff.** Required new tests:

- `exec/interpolation-nested-string/`: `println("${greet("x")}")` → `x`.
- Nested deeper: `"${greet("${greet("y")}")}"` → `y`.
- Call with multiple string args inside `${}`: `"${join("a", "b")}"`.
- A lambda/block inside `${}` containing braces, to exercise the brace counter.
- Regression: confirm `exec/string-interpolation`, `exec/interpolation-edges`, and every
  `golden/interpolation*` / `golden/string-*` stay **byte-identical** (or are updated with explicit
  justification).

Preserve every already-working interpolation rule: simple `$name` stops at the next `$`/non-identifier;
`"$a$b"` → `(a) + (b)`; `"$a.b"` → `a` + literal `.b`; trailing `$` literal; `\$` → literal `$`; empty
`"${}"` → `AFFOGATO_PARSE`; an escape decoding to `"`/`\`/CR/LF still rejected.

---

## Deliverables

For **each** bug: the regression fixture(s), the minimal fix, the **full suite green** (184 goldens
byte-identical unless a change is intentional + justified), generated Java verified with `javac`, and a
short note on the decision taken (for Bug A: diagnostic vs. full support; for Bug B: confirmation the
lexer-mode tokenization handles arbitrary nesting). **Keep the two bugs in separate commits.** Do not
introduce new ANTLR warnings; check the build output. If Bug B's lexer-mode change proves to destabilize
the string goldens beyond clean, justifiable updates, stop and report the diffs rather than forcing it.
```
