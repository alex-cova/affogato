# Affogato for Java Developers

Welcome to Affogato! This guide is designed to help Java developers quickly learn Affogato by comparing its syntax and features directly with Java.

Affogato is an experimental JVM language that transpiles directly to clean Java 21 code, meaning all your existing Java libraries, tools, and JVM runtime knowledge transfer 100%.

---

## 1. Syntax Basics & Files

In Java, every statement must end with a semicolon. In Affogato, semicolons are completely optional.

| Feature | Java | Affogato |
| :--- | :--- | :--- |
| **File Extension** | `.java` | `.aff` |
| **Semicolons** | Required (`;`) | Optional |
| **Main Entry Point** | `public static void main(String[] args)` | `static func main(args: String[])` |

### Hello World in Affogato
```affogato
package com.example

class Hello {
    static func main(args: String[]) {
        println("Hello, Affogato! ☕")
    }
}
```

---

## 2. Variables: `var` and `let`

Java uses `final` to define immutable variables. Affogato adopts a modern approach using `var` and `let`:

- **`var`**: Mutable variable (can be reassigned).
- **`let`**: Immutable variable (equivalent to `final`).

Type inference is fully supported. If you want to specify a type, use the `name: Type` syntax.

```affogato
// Inferred types
var count = 10          // Mutable int
let pi = 3.14159        // Immutable double

// Explicit types
var name: String = "Coffee"
let maxUsers: int = 100
```

---

## 3. Class Definitions & Constructors

Affogato simplifies classes and constructor declarations significantly.

### Inheritance
Instead of `extends` and `implements`, Affogato uses a single colon `:` for inheritance, comma-separating parent classes and interfaces.

```affogato
class Dog : Animal, Runnable { ... }
```

### Compact Class Constructors
When defining a class, you can write the constructor parameters directly in the header. If you prefix parameters with `var` or `let`, Affogato automatically generates matching fields, initializes them, and keeps them in scope.

```affogato
// Affogato
class User(var name: String!, let id: int) {
    func display() {
        println(name + " (#" + id + ")")
    }
}
```
In Java, this would require declaring fields, creating a constructor, and assigning parameters to `this.name` and `this.id`.

### Explicit Constructors (`init`)
If you need custom constructor logic, use the `init` keyword instead of the class name:

```affogato
class User {
    let name: String!
    let id: int

    init(name: String!, id: int) {
        this.name = name
        this.id = id
    }
}
```

---

## 4. Methods and Functions

Methods in Affogato can be written in two ways:
1. **Java-style**: `ReturnType name(Type param)`
2. **Affogato-style**: `name(param: Type): ReturnType`

The keyword `func` is a shorthand alias for `void`.

```affogato
class Calculator {
    // Affogato-style method returning an int
    add(a: int, b: int): int {
        return a + b
    }

    // Java-style method returning a String
    String greet(String name) {
        return "Hello, " + name
    }

    // A void method using 'func'
    func log(message: String) {
        println(message)
    }
}
```

---

## 5. Null Safety

Affogato brings first-class null safety to the JVM without requiring a new runtime.

- **`Type!`**: Non-null reference. The compiler verifies that null cannot be assigned, and injects runtime checks (`Objects.requireNonNull`) at boundaries.
- **`Type?`**: Nullable reference.
- **`Type`**: Platform type (standard Java behavior, no compile-time checks enforced).

```affogato
var name: String! = "Alex"  // Guaranteed non-null
var nickname: String? = null // Allowed to be null

// Compile error!
// let invalid: String! = null

func greet(n: String!) {
    println("Hello, " + n)
}
```

---

## 6. Extension Functions

Affogato allows you to add new methods to existing classes without inheriting from them. This works by defining a static helper with a receiver class prepended to the function name.

```affogato
// Add shout() to java.lang.String
func String.shout(): String {
    return this + "!"
}

// Usage
class Demo {
    static func run() {
        let greeting = "hello".shout()
        println(greeting) // Prints: hello!
    }
}
```

---

## 7. Control Flow

### Guard Clauses
Inspired by Swift, `guard` statements check a condition and force an early exit in the `else` block if the condition is not met. The `else` block **must** exit the control flow via `return`, `throw`, `break`, or `continue`.

```affogato
func processUser(user: User?) {
    guard user != null else {
        return // Early exit
    }
    // 'user' is guaranteed non-null here
    println("Processing: " + user.name)
}
```

### Switch Expressions & Statements
Affogato uses Java-like modern switch syntax using arrows (`->`) and supports switch expressions that return a value.

```affogato
let dayType = switch day {
    case "Saturday", "Sunday" -> "Weekend"
    default -> "Weekday"
}
```

### For-In Loops
Iterate over Java lists, sets, and arrays using the clean `for-in` syntax:

```affogato
let items = ["espresso", "macchiato", "latte"]
for item in items {
    println(item)
}
```

---

## 8. Java Interop & Collections

Affogato is 100% compatible with Java:
- You can instantiate Affogato and Java classes without the `new` keyword: `let list = ArrayList<String>()`.
- Named arguments are supported using `=`: `createUser(name = "Alice", age = 42)`.

### Collection Shortcuts
Affogato provides built-in constructors and shorthands for standard Java collections:
- `List<T>()` instantiates a standard mutable `java.util.ArrayList<T>`.
- `Set<T>()` instantiates a standard mutable `java.util.HashSet<T>`.
- `Map<K, V>()` instantiates a standard mutable `java.util.HashMap<K, V>`.
- `[T]` acts as a shorthand type for `java.util.List<T>`.

```affogato
import java.util.List

class CollectionsDemo {
    static func run() {
        // List<String>() creates an ArrayList
        var items: List<String> = List<String>()
        items.add("Affogato")

        // Shorthand array list initializer
        let numbers: int[] = [1, 2, 3]
        
        // Loop over it
        for n in numbers {
            println("Number: " + n)
        }
    }
}
```
