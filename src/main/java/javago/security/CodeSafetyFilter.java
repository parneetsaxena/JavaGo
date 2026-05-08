package javago.security;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodeSafetyFilter {
    private static final List<String> BLOCKED_FRAGMENTS = List.of(
            "Runtime",
            "ProcessBuilder",
            "File",
            "Socket",
            "System.exit"
    );

    public void validate(String code) {
        if (code == null || code.isBlank()) {
            throw new UnsafeCodeException("No code provided for validation.");
        }

        for (String blocked : BLOCKED_FRAGMENTS) {
            if (code.contains(blocked)) {
                throw new UnsafeCodeException("Rejected by safety filter: " + blocked);
            }
        }
    }
}
