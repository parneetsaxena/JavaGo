package javago.transpiler;

import java.util.List;

public record TranspileResult(String javaCode, String className, List<String> warnings) {
}
