# Affogato IntelliJ Plugin

This module builds a minimal IntelliJ language plugin for `.aff` files using
the IntelliJ Platform Gradle Plugin, Grammar-Kit and JFlex.

Implemented:

- `.aff` file type registration.
- Generated lexer classes from `src/main/jflex/Affogato.flex`.
- Generated PSI/parser classes from `src/main/grammar/Affogato.bnf`.
- Parser definition and syntax highlighter registration.
- Project-level Affogato symbol references, navigation, find usages and rename for
  classes, fields, methods and parameters.
- Basic code completion: keywords, Affogato types, locals, parameters, fields,
  methods and member access after `.` or `?.` (including enum constants, record
  components, qualified chains, `this` and `super`).
- Import-line completion for Affogato and Java types, plus auto-import on symbol
  selection when a type is not visible without an import.
- Java classpath completion: Java types in expressions, instance/static member
  completion after `.` / `?.` (including qualified chains like `System.out`).
- Call-site completion: named argument names inside `(` / `,` positions (with `: `
  insert), constructor/record parameter names, and overload signatures before `(`.
- Completion polish: relevance weighers (same-package types and locals rank above
  cross-package/Java classpath matches), statement snippets (`sout`, `main`), and
  lenient symbol completion near parse errors.

Remaining:

- Broader references that include Java symbols/imports and richer local scopes.
- Static and wildcard import completion.
- Inspections and quick fixes.
