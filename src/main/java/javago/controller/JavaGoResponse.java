package javago.controller;

import java.util.List;

public record JavaGoResponse(
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
