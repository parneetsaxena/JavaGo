package javago.service;

import javago.execution.CompilationResult;
import javago.execution.ExecutionResult;
import javago.execution.ExecutionService;
import javago.security.CodeSafetyFilter;
import javago.transpiler.JavaGoTranspiler;
import javago.transpiler.TranspileResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class JavaGoService {
    private final JavaGoTranspiler transpiler;
    private final CodeSafetyFilter codeSafetyFilter;
    private final ExecutionService executionService;

    public JavaGoService(JavaGoTranspiler transpiler, CodeSafetyFilter codeSafetyFilter, ExecutionService executionService) {
        this.transpiler = transpiler;
        this.codeSafetyFilter = codeSafetyFilter;
        this.executionService = executionService;
    }

    public TranspileOutcome transpile(String sourceCode) {
        TranspileResult result = transpiler.transpile(sourceCode, "Main");
        return new TranspileOutcome(result.javaCode(), result.warnings());
    }

    public CompileOutcome compile(String sourceCode) throws IOException, InterruptedException {
        TranspileResult result = transpiler.transpile(sourceCode, "Main");
        codeSafetyFilter.validate(sourceCode);
        codeSafetyFilter.validate(result.javaCode());

        CompilationResult compilationResult = executionService.compile(result);
        return new CompileOutcome(
                compilationResult.success(),
                result.javaCode(),
                compilationResult.compilerErrors(),
                result.warnings()
        );
    }

    public RunOutcome run(String sourceCode, String input) throws IOException, InterruptedException {
        TranspileResult result = transpiler.transpile(sourceCode, "Main");
        codeSafetyFilter.validate(sourceCode);
        codeSafetyFilter.validate(result.javaCode());

        ExecutionResult executionResult = executionService.run(result, input == null ? "" : input);
        boolean success = executionResult.compilerErrors().isBlank()
                && executionResult.runtimeErrors().isBlank()
                && !executionResult.timedOut();

        String message = success ? "Execution completed." : executionResult.message();
        return new RunOutcome(
                success,
                result.javaCode(),
                executionResult.compilerErrors(),
                executionResult.runtimeOutput(),
                executionResult.runtimeErrors(),
                executionResult.timedOut(),
                result.warnings(),
                message
        );
    }

    public record TranspileOutcome(String transpiledCode, List<String> warnings) {
    }

    public record CompileOutcome(boolean success, String transpiledCode, String compilerErrors, List<String> warnings) {
    }

    public record RunOutcome(
            boolean success,
            String transpiledCode,
            String compilerErrors,
            String runtimeOutput,
            String runtimeErrors,
            boolean timedOut,
            List<String> warnings,
            String message
    ) {
    }
}
