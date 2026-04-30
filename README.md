# JavaGo

JavaGo is a `.javag` to Java transpiler for writing JVM programs with shorter, more direct syntax.

## 🚀 Overview

JavaGo is an experiment in making Java less tedious without leaving the Java ecosystem. Instead of building a new runtime, VM, or language toolchain from scratch, it takes a small custom syntax layer and lowers it into normal `.java` code that can be compiled and run with the standard JDK.

The project currently focuses on the practical parts of that workflow: transpiling source, running it through the JVM, and exposing that flow through a CLI, a Swing UI, and an HTTP backend with a browser frontend. The goal is not to hide Java. The goal is to remove repetitive ceremony while keeping the output transparent and debuggable.

## ❗ Problem

Java is powerful, stable, and widely deployable, but it is also verbose for small programs and quick experiments. A lot of friction comes from boilerplate around input, printing, utility setup, and repetitive syntax that adds noise before useful logic starts.

JavaGo addresses that by letting you write compact source for common tasks, then converting it back into plain Java. You keep JVM compatibility, existing tooling, and readable generated output.

## ⚙️ How It Works

The high-level flow is:

```text
.javag source
   ->
JavaGo transpiler
   ->
.java output
   ->
javac
   ->
JVM execution
```

At the center of the project is `Transpiler.java`, which rewrites JavaGo-specific constructs into standard Java. `Runner.java` handles compile-and-run flow. The CLI, GUI, and server sit on top of that core instead of duplicating transformation logic.

## ✨ Example

JavaGo:

```java
Go.utilities(Scanner sc);

class Demo {
    main() {
        int age = Go.inputPrompt("Enter age: ");
        Go.println("Age:", age);
    }
}
```

Generated Java:

```java
import java.util.Scanner;

class Demo {
    static Scanner sc = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.print("Enter age: ");
        int age = sc.nextInt();
        System.out.println("Age:" + " " + age);
    }
}
```

## 🔥 Features

- `.javag` to standard Java transpilation
- `Go.utilities(...)` for utility imports and object injection
- Scanner-based input using declared Scanner variable names
- `Go.inputPrompt(...)` for prompt + typed input in one step
- `Go.println(...)` smart print with multi-argument support
- `Go.print(...)` smart inline print
- `Go.printArr(...)` using `Arrays.toString(...)`
- `Go.printMatrix(...)` for row-wise matrix printing
- `Go.swap(arr, i, j)` expansion for array element swapping
- CLI transpile and run flow
- Swing desktop interface
- HTTP backend with browser frontend

## 📦 Setup / Usage

Build:

```powershell
New-Item -ItemType Directory -Force -Path target\classes | Out-Null
$sources = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -d target\classes $sources
```

Transpile a file:

```powershell
java -cp target\classes cli.JavaGoCLI transpile examples\test.javag
```

Run a file:

```powershell
java -cp target\classes cli.JavaGoCLI run examples\test.javag
```

Start the HTTP server:

```powershell
java -cp target\classes ui.JavaGoServer
```

Start the Swing UI:

```powershell
java -cp target\classes ui.JavaGoGUI
```

## 📊 Current Status

Completed:
- core transpiler pipeline
- utility injection
- smart print transforms
- Scanner-driven input transforms
- CLI run/transpile flow
- Swing UI and browser-connected backend

In Progress:
- cleaner separation inside the transpiler core
- broader example coverage and regression testing
- tightening generated Java formatting and edge-case handling

Planned:
- more language constructs beyond utility helpers
- stronger parser structure instead of piling logic into one transpiler file
- packaging and build automation

## 🧠 Why This Exists

This project exists because there is a gap between "Java is production-proven" and "Java is pleasant for rapid expression." JavaGo explores whether that gap can be narrowed with a thin transpilation layer instead of a full language fork.

The point is developer ergonomics with low ecosystem friction: write less ceremony, keep the JVM, and make the generated code visible enough that nothing feels magical.

## 🔮 Future Scope

The next useful direction is deeper syntax support with a more structured transformation pipeline. That includes improving parsing, expanding the standard helper surface, and making the language layer more consistent without losing direct Java output.

Longer term, JavaGo could grow into a stronger authoring layer for JVM development, with better abstractions, smarter tooling support, and possibly analysis-assisted transforms where they actually improve code generation instead of obscuring it.

## 📄 License

This project is licensed under the MIT License. See the LICENSE file for details.
