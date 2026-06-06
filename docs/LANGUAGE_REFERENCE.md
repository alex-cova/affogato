# Affogato Language Reference

## Production Target

Affogato currently targets small JVM apps and libraries. The compiler emits Java
21 source and validates the production subset before writing generated files.

## Syntax

- Packages and imports mirror Java syntax, with optional semicolons.
- Static imports use `import static package.Type.member`.
- Classes use `class Name { ... }`; inheritance and interface implementation use
  `class Child : Parent, Interface`.
- Compact constructors are supported: `class User(var name: String!, let id: int)`.
- Explicit constructors use `init(...) { ... }`.
- Records, enums and interfaces are supported, including interface `default`
  methods.
- Methods may use Java-style return-first syntax (`String name()`) or Affogato
  syntax (`name(): String`). `func` means `void`.
- Locals and fields use `var` for mutable bindings and `let` for immutable
  bindings.
- Statements are separated by a newline or `;`. The closing `}` of a block also
  ends the final statement, so single-line bodies like `func f() { doThing() }`
  are valid; a missing separator *between* statements on one line is still an
  error.
- Control flow includes `if`, `guard ... else`, `for ... in`, `while`, `switch`,
  `try`, `catch`, multi-catch, `finally`, `return` and `throw`.
- Expressions include literals, identifiers, calls, constructors, property
  access, assignment, binary/unary operators, casts, lambdas, method references,
  ternary expressions and switch expressions.
- `[T]` is shorthand for `java.util.List<T>` (e.g. `Supplier<[Component]>`).
- A trailing closure on a call/constructor binds to the last parameter:
  `Button(text = "Hi") { ... }` lowers the block to a lambda for the trailing
  parameter. When that parameter is a `Supplier<[T]>`, the block becomes a
  result builder that collects each child expression into a list, enabling DSLs
  such as `Panel { Label(...) Button(...) { ... } }`.
- String interpolation: `"Hello ${name}"` and `"count $count"` lower to Java
  string concatenation. Use `\$` for a literal dollar sign. Embedded expressions
  follow normal Affogato expression syntax inside `${ ... }`.
  - The simple `$name` form reads a single identifier and stops at the next `$` or
    non-identifier character, so `"$a$b"` interpolates `a` then `b` and `"$a.b"` is
    `a` followed by the literal text `.b`. Use the `${ ... }` form for member access
    (`"${a.b}"`) or for a name that contains `$`.
  - An empty interpolation `"${}"` is a compile error (`AFFOGATO_PARSE`).
  - Interpolated expressions may contain string literals, including nested
    interpolation such as `"${greet("${name}")}"`.
- Extension functions: `func ReceiverType.member(...): ReturnType { ... }` at
  top level become static methods on a generated holder class; inside the body,
  `this` is the receiver and bare member names resolve on the receiver type.

## Identifiers And Literals

- Identifiers use Java-style Unicode letters, digits, `_` and `$` (the lexer
  accepts non-ASCII identifier characters per `JavaLetter` / `JavaLetterOrDigit`).
- String literals use `"..."` with escapes `\t`, `\n`, `\b`, `\r`, `\f`, `\"`,
  `\'`, and `\\`. `\uXXXX` escapes are supported, except that an escape decoding
  to `"`, `\`, CR or LF is rejected (`AFFOGATO_PARSE`) — use the direct escape
  (`\"`, `\\`, `\r`, `\n`) so the generated Java stays well-formed.
- Integer literals may use digit separators (`1_000`); a separator may not be
  leading or trailing. Suffixes `l`/`L` mark `long`. Hexadecimal literals
  (`0xFF`, `0x1FL`) are supported. Leading-zero/octal and binary literals are not
  (a leading-zero integer such as `010` is rejected as `AFFOGATO_NUMERIC_LITERAL`).

## Operator Precedence

From highest to lowest binding (Java-like):

1. Postfix: calls, `.member`, `++`/`--` postfix
2. Unary: `!`, `-`, `~`, prefix `++`/`--`, `not(...)`
3. Multiplicative: `*`, `/`, `%`
4. Additive: `+`, `-`
5. Cast: `expr as Type` (left-associative; `expr as A as B` chains to `((B)((A) expr))`)
6. Shift: `<<`, `>>`, `>>>` (numeric operands; result type follows the left operand, e.g. `1 << 4` is `int`, `1L << 40` is `long`). Compound shift-assignment (`<<=`, `>>=`, `>>>=`) is not supported.
7. Relational: `<`, `<=`, `>`, `>=`, `is Type`
8. Equality: `==`, `!=` (left-associative; `a == b == c` is `(a == b) == c`)
9. Bitwise: `&`, `^`, `|`
10. Logical: `&&`, `||`
11. Ternary: `? :`
12. Assignment: `=`, `+=`, … (right-associative; `a = b = c` is `a = (b = c)`)

## Nullability

- `Type?` marks a nullable reference type.
- `Type!` marks a non-null reference type and emits runtime annotations/checks
  where the compiler owns the declaration.
- Assigning `null` to a non-null type is a compile error.
- Safe calls (`?.`), Elvis (`?:`) and not-null assertions (`!!`) are not in the
  production subset.

## Java Interop

- Affogato resolves Java constructors, methods, public/package-visible fields,
  getters/setters, inherited methods and interface default methods from the
  compile classpath.
- Named Java arguments require parameter metadata from Java compilation with
  `-parameters`; positional calls do not.
- `List<T>()`, `Set<T>()` and `Map<K, V>()` lower to standard mutable Java
  collection implementations.
- Lambdas and method references are supported for Java functional interfaces.
- Static imports participate in call resolution.

## Overloads And Named Arguments

- Overload resolution uses practical Java-like strict, loose and varargs phases.
- Named arguments are reordered after a single overload is selected.
- Ambiguous overloads fail with a diagnostic rather than generating Java that may
  fail later.
- Full JLS corner cases such as target-typed poly expressions and capture
  conversion are known limitations.

## Diagnostics

The compiler emits stable `AFFOGATO_*` codes. CLI and Gradle render multi-line
output with file location, source snippet, caret, and hint when source is
available:

```text
AFFOGATO_IDENTIFIER_RESOLUTION: Cannot resolve identifier total.
  at App.aff:3:9

3 |         return total
            ^^^^^
Hint: Declare the name before use, import it, or fix the spelling.
```

IntelliJ shows the same hint inline in the annotation tooltip.

### Diagnostic catalog

| Code | Meaning |
|---|---|
| `AFFOGATO_ARRAY_ACCESS_TYPE` | `[]` used on a non-array/list receiver |
| `AFFOGATO_ARRAY_INDEX_TYPE` | Array index is not int-compatible |
| `AFFOGATO_ASSIGNMENT_ARGUMENT` | `name = value` used as a call argument where `name` is a variable in scope (read as a named argument, not an assignment) |
| `AFFOGATO_ASSIGNMENT_TYPE` | Initializer or assignment type mismatch |
| `AFFOGATO_CALL_RESOLUTION` | Unresolved method or function call |
| `AFFOGATO_CAST_TYPE` | Invalid cast target or source |
| `AFFOGATO_CATCH_TYPE` | Catch type not assignable from thrown type |
| `AFFOGATO_CLASS_LITERAL_TYPE` | Class literal uses erased type parameter |
| `AFFOGATO_COMPACT_PARAM` | Invalid compact constructor parameter |
| `AFFOGATO_CONDITION_TYPE` | Non-boolean condition or logical operand |
| `AFFOGATO_CONSTRUCTOR_RESOLUTION` | Unresolved constructor call |
| `AFFOGATO_DUPLICATE_CLASS` | Duplicate top-level type name |
| `AFFOGATO_DUPLICATE_LOCAL` | Duplicate `let`/`var` in the same block |
| `AFFOGATO_EXTENSION_PARAM_CONFLICT` | Extension parameter shadows member |
| `AFFOGATO_FIELD_TYPE` | Field initializer type mismatch |
| `AFFOGATO_FOR_ITERABLE_TYPE` | `for-in` requires array or `Iterable` |
| `AFFOGATO_GUARD_FLOW` | Guard else block must exit (return/throw) |
| `AFFOGATO_IDENTIFIER_RESOLUTION` | Unknown identifier |
| `AFFOGATO_IMPORT_CONFLICT` | Conflicting imports |
| `AFFOGATO_INSTANCEOF_TYPE` | `is` requires reference type |
| `AFFOGATO_IO` | Source file read failure |
| `AFFOGATO_JAVA_RELEASE` | Unsupported `--release` (currently 21 only) |
| `AFFOGATO_LET_ASSIGN` | Assignment to immutable `let` or final field |
| `AFFOGATO_LOCAL_TYPE` | Local needs explicit type (e.g. null init) |
| `AFFOGATO_MAIN_SIGNATURE` | Static `main` is not the runnable `main(args: String[])` entry point (warning) |
| `AFFOGATO_NAMED_ARGS` | Named arguments cannot map to callable |
| `AFFOGATO_NUMERIC_LITERAL` | Leading-zero/octal literal, or integer literal out of range for int/long |
| `AFFOGATO_OPERATOR_TYPE` | Operator applied to incompatible types |
| `AFFOGATO_PARSE` | Syntax or lexer error |
| `AFFOGATO_POLY_TARGET_TYPE` | Lambda/method ref needs target type |
| `AFFOGATO_PROPERTY_MUTATION_EXPR` | Property `++`/`--`/`+=` inside a larger expression |
| `AFFOGATO_PROPERTY_RESOLUTION` | Unknown property on known receiver |
| `AFFOGATO_RECORD_MEMBER` | Invalid record member access |
| `AFFOGATO_RETURN_FLOW` | Non-void method may complete without value |
| `AFFOGATO_RETURN_TYPE` | Return expression type mismatch |
| `AFFOGATO_SOURCE_SCAN` | Cannot scan source directory |
| `AFFOGATO_SWITCH_EXPR_BODY` | Invalid switch expression arm |
| `AFFOGATO_SWITCH_LABEL_TYPE` | Switch label type mismatch |
| `AFFOGATO_SWITCH_SELECTOR_TYPE` | Invalid switch selector type |
| `AFFOGATO_TERNARY_TYPE` | Incompatible ternary branch types |
| `AFFOGATO_THROW_TYPE` | Throw expression not a `Throwable` |
| `AFFOGATO_TYPE_RESOLUTION` | Unknown type reference |
| `AFFOGATO_UNREACHABLE` | Statement is unreachable (warning) |
| `AFFOGATO_USE_BEFORE_INIT` | Local used before its declaration in the same block |
| `AFFOGATO_UNSUPPORTED_ELVIS` | `?:` outside production subset |
| `AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION` | `!!` outside production subset |
| `AFFOGATO_UNSUPPORTED_SAFE_CALL` | `?.` outside production subset |
| `AFFOGATO_WRITE` | Failed to write generated Java |
