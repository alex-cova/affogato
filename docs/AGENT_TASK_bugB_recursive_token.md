# Agent Task — Bug B (string-in-interpolation), **Plan B: recursive token rule** (less invasive)

You are a **senior compiler / ANTLR engineer** working on **Affogato**, a language that transpiles
source-to-source to **Java 21**. The compiler lives in `affogato-compiler/`. Work **test-first** and **do
not break a single golden test**.

This is an **alternative implementation of Bug B** described in
[`AGENT_TASK_remaining_fragility.md`](AGENT_TASK_remaining_fragility.md). That file specifies the
**lexer-modes** (full re-tokenization) approach. **This file specifies the smaller, lower-risk
recursive-token-rule approach.** Read the "Architecture" and "Commands" / "Test harnesses" /
"Constraints" sections of that file first — they apply verbatim. Pick **one** approach; do not do both.

> Bug A (property mutation in expression position) is unrelated and out of scope here — see the other
> file.

---

## The bug (recap)

```aff
class A {
  greet(s: String): String { return s }
  static func run() {
    println("${greet("x")}")     // AFFOGATO_PARSE: mismatched input 'x' ...
  }
}
```

In `affogato-compiler/src/main/antlr/dev/affogato/compiler/parser/Affogato.g4` a string is a single
token whose `StringCharacter` excludes `"`, so the inner `"` of `${greet("x")}` ends the string early:

```antlr
StringLiteral : '"' StringCharacter* '"' ;
fragment StringCharacter : ~["\\\r\n] | EscapeSequence ;
```

The transpiler treats `"..."` as **one token** and `transformStringInterpolation(String, MethodContext)`
(in `AffogatoTranspiler.java`, ~line 2619) re-parses that text itself — it **already** has
`findMatchingBraceSkippingStrings(...)` that correctly skips nested string literals. The only thing
missing is that the **lexer hands it a truncated string**.

## Core idea of Plan B

Keep the string as **exactly one token** (so the parser, the `literal` rule, `transformStringInterpolation`,
`validateStringEscapes`, and every `AffogatoLexer.StringLiteral` consumer stay unchanged), but make the
`StringLiteral` lexer rule able to **consume a balanced `${ ... }` group** — including nested quotes,
escapes, and nested `${ ... }` — as part of the same token. ANTLR allows **recursive lexer rules**, so the
token rule can call a fragment that recurses.

### Grammar change (sketch — adapt to the existing escape set `\t \n \b \r \f \" \' \\` and `\uXXXX`)

```antlr
StringLiteral
    : '"' (StringCharacter | Interpolation)* '"'
    ;

fragment StringCharacter
    : ~["\\\r\n]          // unchanged: plain char (NOT '"', '\', CR, LF)
    | EscapeSequence
    ;

// A '${' ... '}' group that may contain plain chars, escapes, nested strings, and nested ${...}.
// Note: a nested string can itself contain newlines? No — keep StringLiteral single-line as today;
// the inner StringLiteral fragment reuses the same single-line StringCharacter set.
fragment Interpolation
    : '$' '{' ( EscapeSequence | StringLiteral | Interpolation | ~["{}\\] )* '}'
    ;
```

### The critical risk: lexer ambiguity / longest-match

`StringCharacter`'s `~["\\\r\n]` matches a single `$` or `{`. So when the lexer is inside the
`(StringCharacter | Interpolation)*` loop and sees `${`, it must prefer the **`Interpolation`** branch
over consuming `$` as a lone `StringCharacter`. ANTLR lexer alternation inside a subrule is **not**
guaranteed to pick the longest match the way you might expect — you must **verify empirically** and, if
needed, disambiguate:

- Reorder so `Interpolation` is tried first, and/or
- Make `StringCharacter` explicitly **not** start an interpolation: change the plain-char alternative to
  exclude a `$` that is immediately followed by `{`. Since ANTLR lexer rules can't easily do
  "negative lookahead" in a char set, the robust trick is to keep `Interpolation` as a separate
  alternative listed first and confirm with tests that `${...}` is consumed as a whole. If ANTLR still
  splits it, fall back to the lexer-modes approach (the other file).

You **must** prove the following all tokenize as **one** `StringLiteral` token (write a lexer fixture or a
tiny JUnit that dumps the token stream):

| Input | Must be ONE StringLiteral token |
|---|---|
| `"plain"` | yes |
| `"$name"` (simple form, `$` then identifier) | yes |
| `"${x}"` | yes |
| `"${greet("x")}"` | yes (inner string consumed) |
| `"a ${b} c"` | yes |
| `"${f("${g}")}"` (nested interpolation with nested string) | yes |
| `"\$literal"` (escaped dollar) | yes, and not treated as interpolation |
| `"xAy"` (unicode escape still works) | yes |

### Codegen — should need (almost) no change

`transformStringInterpolation` already:
- splits on `${ ... }` using `findMatchingBraceSkippingStrings` (skips nested quotes),
- handles `$name`, `\$`, empty `${}` → `AFFOGATO_PARSE`, the leading `"" +` rule, etc.

Once the lexer delivers the **full** string token (e.g. `"${greet("x")}"`), the existing method should
lower it correctly: the embedded `greet("x")` becomes `(greet("x"))` and the rest of the pipeline runs on
it. **Confirm this end-to-end**; only touch `transformStringInterpolation` if a nested case reveals a real
gap in its brace/quote scanning.

### Things to double-check (Plan B keeps the token shape, so these are low-risk but verify)

- `AffogatoTranspiler.validateStringEscapes(Path, Token)` still iterates one `StringLiteral` token whose
  text now *includes* the `${...}` body. Its `\u` safety check (rejecting escapes that decode to
  `"`/`\`/CR/LF) currently scans the whole token text — make sure it does **not** false-positive on a
  legitimate `"` that now lives inside `${...}` (a real quote char, not an escape). It only flags
  **escape sequences** `\uXXXX`, so a literal inner `"` is fine, but add a test:
  `"${greet("a")}"` must NOT raise `AFFOGATO_PARSE` from escape validation.
- `scanUnsupportedSourceEdges` / `scanUnsupportedToken` (raw-source scans for `?.`, `?:`, `!!` that skip
  string contents): these scan raw text and treat `"` as string boundaries; with a quote now legitimately
  inside `${...}`, confirm they don't mis-detect. Add a probe: `"${a ?: b}"`... actually `?:` is
  unsupported anyway, but `"${m["k"]}"`-style content should not break the scanner. Re-run the negative
  fixtures for `?.`/`?:`/`!!`.
- `findMatchingBraceSkippingStrings` and the `$`-handling in `transformStringInterpolation`: re-read them
  with deeply nested input in mind.

### IntelliJ grammar

Update `affogato-intellij-plugin/src/main/grammar/Affogato.flex` so its string rule matches (or confirm
the plugin's existing handling still builds). The IntelliJ `Affogato.bnf` uses a flat model and likely
needs no change, but the JFlex lexer (`.flex`) does the string tokenization and may need the same
balanced-`${}` treatment. At minimum the plugin module must still build.

---

## Tests (required)

- `exec/interpolation-nested-string/`: `println("${greet("x")}")` → `x`.
- `exec/interpolation-nested-deep/`: `println("${greet("${greet("y")}")}")` → `y`.
- `exec/interpolation-multi-arg/`: `"${join("a", "b")}"`.
- A lexer/token-dump test proving the table above (all single tokens).
- **Regression:** every `golden/interpolation*`, `golden/string-*`, `golden/string-escapes`,
  `exec/string-interpolation`, `exec/interpolation-edges` stays **byte-identical**, and the negative
  fixtures for `?.`/`?:`/`!!`, unclosed strings, invalid escapes, and the `\u`→`"`/`\`/CR/LF safety check
  all still pass.
- Update `docs/LANGUAGE_REFERENCE.md` (it currently states string literals are **not** allowed inside
  `${...}` and tells users to bind to a local — update that text).

## Deliverable

The grammar change, a token-stream test proving non-ambiguous tokenization of all the cases above, the
exec fixtures, the **full suite green** (184 goldens byte-identical or justified), generated Java verified
with `javac`, and a short note. **If you cannot make ANTLR tokenize `${...}` unambiguously as one token**
(the alternation/longest-match risk above), **stop and switch to the lexer-modes plan**
([`AGENT_TASK_remaining_fragility.md`](AGENT_TASK_remaining_fragility.md)) rather than shipping a fragile
lexer. Do not introduce new ANTLR build warnings.
```
