package cli;

import core.Runner;
import core.Transpiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JavaGoCLI {
    private JavaGoCLI() {
    }

    public static void main(String[] args) {
        int exitCode = new JavaGoCLI().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private int run(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return args.length == 0 ? 1 : 0;
        }

        if (args.length < 2) {
            System.err.println("Error: Missing file path.");
            printHelp();
            return 1;
        }

        String command = args[0];
        String filePath = args[1];
        Path inputFile = Paths.get(filePath).toAbsolutePath().normalize();

        if (!Files.exists(inputFile)) {
            System.err.println("Error: File not found: " + filePath);
            return 1;
        }

        Runner runner = new Runner();

        try {
            if ("transpile".equals(command)) {
                Transpiler.Result result = runner.transpileFile(inputFile, null);
                Path outputPath = inputFile.resolveSibling(result.getClassName() + ".java");
                System.out.println("Generated " + outputPath);
                for (String warning : result.getWarnings()) {
                    System.out.println(warning);
                }
                return 0;
            }

            if ("run".equals(command)) {
                Runner.RunResult result = runner.runFile(inputFile, false);
                if (!result.getOutput().isBlank()) {
                    System.out.print(result.getOutput());
                }
                return result.isSuccess() ? 0 : 1;
            }

            System.err.println("Error: Invalid command: " + command);
            printHelp();
            return 1;
        } catch (Exception ex) {
            System.err.println("Error: " + ex.getMessage());
            return 1;
        }
    }

    private void printHelp() {
        System.out.println("JavaGo CLI");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  javago transpile <file.javag>");
        System.out.println("  javago run <file.javag>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  javago transpile examples\\test.javag");
        System.out.println("  javago run examples\\test.javag");
    }
}
