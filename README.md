# JavaGo

JavaGo is a JVM-compatible transpiler that converts `.javag` source into standard Java, with a CLI, desktop UI, and lightweight HTTP server.

## Structure

```text
JavaGo/
├── src/
│   ├── cli/
│   │   └── JavaGoCLI.java
│   ├── core/
│   │   ├── Runner.java
│   │   └── Transpiler.java
│   └── ui/
│       ├── JavaGoGUI.java
│       ├── JavaGoServer.java
│       ├── javago-demo.html
│       └── javago-frontend.html
├── examples/
│   ├── modifiers.javag
│   ├── println.javag
│   ├── scanner.javag
│   ├── test.javag
│   └── testing.javag
├── docs/
│   ├── architecture.md
│   ├── Gini.png
│   ├── GitProfilePic.psd
│   └── shyaampng.png
├── tests/
│   └── TranspilerSmokeTest.java
├── .gitignore
├── README.md
└── javago.bat
```

## What Lives Where

- `src/core`: transpilation and Java execution workflow
- `src/cli`: command-line entry point
- `src/ui`: Swing app, HTTP server, and browser UI files
- `examples`: sample `.javag` programs
- `docs`: architecture notes and project assets
- `tests`: simple smoke tests

## Build

```powershell
New-Item -ItemType Directory -Force -Path target\classes | Out-Null
$sources = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -d target\classes $sources
```

## Run

CLI:

```powershell
java -cp target\classes cli.JavaGoCLI transpile examples\test.javag
```

Server:

```powershell
java -cp target\classes ui.JavaGoServer
```

Swing UI:

```powershell
java -cp target\classes ui.JavaGoGUI
```
