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

Common stable diagnostic codes include:

- `AFFOGATO_PARSE`: syntax/parser error.
- `AFFOGATO_TYPE_RESOLUTION`: unknown type.
- `AFFOGATO_CALL_RESOLUTION`: unresolved method/function call.
- `AFFOGATO_CONSTRUCTOR_RESOLUTION`: unresolved constructor call.
- `AFFOGATO_PROPERTY_RESOLUTION`: unresolved property on a known receiver.
- `AFFOGATO_RETURN_TYPE`: returned expression is incompatible with method type.
- `AFFOGATO_RETURN_FLOW`: non-void method may complete without a value.
- `AFFOGATO_ASSIGNMENT_TYPE`: initializer or assignment is incompatible.
- `AFFOGATO_CONDITION_TYPE`: condition is not boolean.
- `AFFOGATO_NAMED_ARGS`: named arguments cannot be mapped to a callable.
- `AFFOGATO_UNSUPPORTED_SAFE_CALL`: `?.` is outside the production subset.
- `AFFOGATO_UNSUPPORTED_ELVIS`: `?:` is outside the production subset.
- `AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION`: `!!` is outside the production
  subset.
