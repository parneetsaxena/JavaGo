package javago.execution;

public record ExecutionResult(
        String compilerErrors,
        String runtimeOutput,
        String runtimeErrors,
        boolean timedOut,
        String message
) {
}
