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

Remaining:

- Formatter, brace matcher and code style settings.
- Broader references that include Java symbols/imports and richer local scopes.
- Inspections and quick fixes.
- Compiler diagnostics surfaced inside the IDE.
