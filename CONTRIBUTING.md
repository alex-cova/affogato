# Contributing to Affogato

Thanks for helping improve Affogato.

## Before you start

- Read [docs/LANGUAGE_REFERENCE.md](docs/LANGUAGE_REFERENCE.md) for the supported language subset.
- Read [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) for build commands, test harnesses, and the dual-grammar workflow.

## Pull requests

1. Branch from `main`.
2. Run `GRADLE_USER_HOME=.gradle ./gradlew build` locally (matches CI).
3. Include tests for behaviour changes:
   - `golden/` for codegen output
   - `negative/` for diagnostics
   - `exec/` for runtime behaviour
4. Update language docs when user-visible syntax or diagnostics change.
5. If you touch syntax, sync **both** ANTLR (`Affogato.g4`) and IntelliJ (`Affogato.bnf` / `Affogato.flex`).

## Commit style

Use clear, complete sentences in commit messages. Focus on *why* the change matters.

## Questions

Open an issue for design questions before large refactors (especially transpiler architecture or grammar overhauls).
