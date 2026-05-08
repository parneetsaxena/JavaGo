package javago.execution;

import javago.transpiler.TranspileResult;
import javago.util.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
public class ExecutionService {
    private static final long COMPILE_TIMEOUT_SECONDS = 10;
    private static final long RUN_TIMEOUT_SECONDS = 5;

    private final WorkspaceManager workspaceManager;

    public ExecutionService(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public CompilationResult compile(TranspileResult transpiled) throws IOException, InterruptedException {
        Path workspace = workspaceManager.createWorkspace();
        try {
            Path javaFile = writeJavaFile(workspace, transpiled);
            ProcessResult compileResult = execute(new ProcessBuilder("javac", javaFile.getFileName().toString())
                    .directory(workspace.toFile()), "", COMPILE_TIMEOUT_SECONDS);

            if (compileResult.timedOut()) {
                return new CompilationResult(false, "Compilation timed out.");
            }

            String compilerErrors = firstNonBlank(compileResult.stderr(), compileResult.stdout());
            return new CompilationResult(compileResult.exitCode() == 0, compileResult.exitCode() == 0 ? "" : compilerErrors);
        } finally {
            workspaceManager.cleanup(workspace);
        }
    }

    public ExecutionResult run(TranspileResult transpiled, String input) throws IOException, InterruptedException {
        Path workspace = workspaceManager.createWorkspace();
        try {
            Path javaFile = writeJavaFile(workspace, transpiled);
            ProcessResult compileResult = execute(new ProcessBuilder("javac", javaFile.getFileName().toString())
                    .directory(workspace.toFile()), "", COMPILE_TIMEOUT_SECONDS);

            if (compileResult.timedOut()) {
                return new ExecutionResult("Compilation timed out.", "", "", false, "Compilation timed out.");
            }

            if (compileResult.exitCode() != 0) {
                return new ExecutionResult(firstNonBlank(compileResult.stderr(), compileResult.stdout()), "", "", false, "Compilation failed.");
            }

            ProcessResult runResult = execute(new ProcessBuilder("java", "-cp", ".", transpiled.className())
                    .directory(workspace.toFile()), input, RUN_TIMEOUT_SECONDS);

            if (runResult.timedOut()) {
                return new ExecutionResult("", runResult.stdout(), runResult.stderr(), true, "Execution timed out after 5 seconds.");
            }

            String runtimeErrors = runResult.exitCode() == 0 ? "" : firstNonBlank(runResult.stderr(), runResult.stdout());
            return new ExecutionResult("", runResult.stdout(), runtimeErrors, false,
                    runResult.exitCode() == 0 ? "Execution completed." : "Execution failed.");
        } finally {
            workspaceManager.cleanup(workspace);
        }
    }

    private Path writeJavaFile(Path workspace, TranspileResult transpiled) throws IOException {
        Path javaFile = workspace.resolve(transpiled.className() + ".java");
        Files.writeString(javaFile, transpiled.javaCode(), StandardCharsets.UTF_8);
        return javaFile;
    }

    private ProcessResult execute(ProcessBuilder builder, String input, long timeoutSeconds) throws IOException, InterruptedException {
        Process process = builder.start();

        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        stdout.start();
        stderr.start();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            if (input != null && !input.isEmpty()) {
                writer.write(input);
            }
            writer.flush();
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        stdout.join();
        stderr.join();

        int exitCode = finished ? process.exitValue() : -1;
        return new ProcessResult(stdout.text(), stderr.text(), exitCode, !finished);
    }

    private String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return secondary == null ? "" : secondary;
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
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private record ProcessResult(String stdout, String stderr, int exitCode, boolean timedOut) {
    }
}
