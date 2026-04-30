package core;

import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class Runner {
    private final Transpiler transpiler = new Transpiler();

    public Transpiler.Result transpileFile(Path inputFile, Path outputFile) throws IOException {
        String source = Files.readString(inputFile, StandardCharsets.UTF_8);
        String fallbackClassName = stripExtension(inputFile.getFileName().toString());
        Transpiler.Result result = transpiler.transpile(source, fallbackClassName);
        Path target = outputFile == null ? inputFile.resolveSibling(result.getClassName() + ".java") : outputFile;
        Files.writeString(target, result.getJavaCode(), StandardCharsets.UTF_8);
        return result;
    }

    public RunResult runFile(Path inputFile, boolean verbose) throws IOException, InterruptedException {
        String source = Files.readString(inputFile, StandardCharsets.UTF_8);
        String fallbackClassName = stripExtension(inputFile.getFileName().toString());
        Transpiler.Result transpiled = transpiler.transpile(source, fallbackClassName);
        Path javaFile = inputFile.resolveSibling(transpiled.getClassName() + ".java");
        Files.writeString(javaFile, transpiled.getJavaCode(), StandardCharsets.UTF_8);

        ProcessResult compile = compile(javaFile);
        if (compile.exitCode != 0) {
            return new RunResult(false, compilerMessage(compile), transpiled.getJavaCode(), javaFile);
        }

        ProcessResult run = runClass(javaFile.getParent(), transpiled.getClassName(), true);
        String output = run.exitCode == 0 ? run.stdout : firstNonBlank(run.stderr, run.stdout);

        return new RunResult(run.exitCode == 0, withWarnings(transpiled, output), transpiled.getJavaCode(), javaFile);
    }

    public RunResult compileAndRun(String javaCode, String className) throws IOException, InterruptedException {
        return compileAndRun(javaCode, className, "");
    }

    public RunResult compileAndRun(String javaCode, String className, String input) throws IOException, InterruptedException {
        String effectiveClassName = className == null || className.isBlank() ? "Test" : className;
        Path workspace = Files.createTempDirectory("javago-gui-");
        Path javaFile = workspace.resolve(effectiveClassName + ".java");
        Files.writeString(javaFile, javaCode, StandardCharsets.UTF_8);

        ProcessResult compile = compile(javaFile);
        if (compile.exitCode != 0) {
            return new RunResult(false, compilerMessage(compile), javaCode, javaFile);
        }

        ProcessResult run = runClass(workspace, effectiveClassName, false, input);
        return new RunResult(run.exitCode == 0, run.exitCode == 0 ? run.stdout : firstNonBlank(run.stderr, run.stdout), javaCode, javaFile);
    }

    private ProcessResult compile(Path javaFile) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("javac", javaFile.getFileName().toString());
        builder.directory(javaFile.getParent().toFile());
        return execute(builder, true);
    }

    private ProcessResult runClass(Path workingDirectory, String className, boolean inheritInput) throws IOException, InterruptedException {
        return runClass(workingDirectory, className, inheritInput, "");
    }

    private ProcessResult runClass(Path workingDirectory, String className, boolean inheritInput, String input) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("java", "-cp", ".", className);
        builder.directory(workingDirectory.toFile());
        if (inheritInput) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        }
        return execute(builder, !inheritInput, input);
    }

    private ProcessResult execute(ProcessBuilder builder, boolean closeInput) throws IOException, InterruptedException {
        return execute(builder, closeInput, "");
    }

    private ProcessResult execute(ProcessBuilder builder, boolean closeInput, String input) throws IOException, InterruptedException {
        Process process = builder.start();
        if (closeInput) {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                if (input != null && !input.isEmpty()) {
                    writer.write(input);
                    writer.flush();
                }
            }
        }

        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        stdout.start();
        stderr.start();

        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            stdout.join();
            stderr.join();
            return new ProcessResult(stdout.text(), "Process timed out.", 1);
        }

        int exitCode = process.exitValue();
        stdout.join();
        stderr.join();
        return new ProcessResult(stdout.text(), stderr.text(), exitCode);
    }

    private String compilerMessage(ProcessResult result) {
        return firstNonBlank(result.stderr, result.stdout);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String withWarnings(Transpiler.Result result, String output) {
        if (result.getWarnings().isEmpty()) {
            return output;
        }

        StringBuilder text = new StringBuilder();
        for (String warning : result.getWarnings()) {
            text.append(warning).append(System.lineSeparator());
        }
        if (output != null && !output.isBlank()) {
            text.append(output);
        }
        return text.toString();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    public static final class RunResult {
        private final boolean success;
        private final String output;
        private final String javaCode;
        private final Path javaFile;

        public RunResult(boolean success, String output, String javaCode, Path javaFile) {
            this.success = success;
            this.output = output == null ? "" : output;
            this.javaCode = javaCode;
            this.javaFile = javaFile;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getJavaCode() {
            return javaCode;
        }

        public Path getJavaFile() {
            return javaFile;
        }
    }

    private static final class ProcessResult {
        private final String stdout;
        private final String stderr;
        private final int exitCode;

        private ProcessResult(String stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private StreamCollector(InputStream input) {
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
            }
        }

        private String text() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
