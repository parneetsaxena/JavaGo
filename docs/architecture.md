# JavaGo Architecture

## Core

`core.Transpiler` owns source transformation.

`core.Runner` owns transpile/compile/run workflow for generated Java.

## Interfaces

- `cli.JavaGoCLI`: terminal entry point
- `ui.JavaGoGUI`: local Swing interface
- `ui.JavaGoServer`: HTTP backend for the browser UI

## Frontend

`src/ui/javago-frontend.html` is the browser client served by `ui.JavaGoServer`.

## Direction

Keep the repo shallow. If the transpiler grows, split `src/core` by responsibility inside that folder rather than adding extra top-level layers too early.
