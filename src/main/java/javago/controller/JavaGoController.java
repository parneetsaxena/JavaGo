package javago.controller;

import javago.security.UnsafeCodeException;
import javago.service.JavaGoService;
import javago.service.JavaGoService.CompileOutcome;
import javago.service.JavaGoService.RunOutcome;
import javago.service.JavaGoService.TranspileOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
@CrossOrigin(origins = "*")
public class JavaGoController {
    private final JavaGoService javaGoService;

    public JavaGoController(JavaGoService javaGoService) {
        this.javaGoService = javaGoService;
    }

    @PostMapping("/transpile")
    public ResponseEntity<JavaGoResponse> transpile(@RequestBody(required = false) JavaGoRequest request) {
        try {
            TranspileOutcome outcome = javaGoService.transpile(requiredCode(request));
            return ResponseEntity.ok(new JavaGoResponse(
                    true,
                    outcome.transpiledCode(),
                    null,
                    null,
                    null,
                    false,
                    outcome.warnings(),
                    "Transpile completed."
            ));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (Exception ex) {
            return serverError(ex);
        }
    }

    @PostMapping("/compile")
    public ResponseEntity<JavaGoResponse> compile(@RequestBody(required = false) JavaGoRequest request) {
        try {
            CompileOutcome outcome = javaGoService.compile(requiredCode(request));
            return ResponseEntity.ok(new JavaGoResponse(
                    outcome.success(),
                    outcome.transpiledCode(),
                    outcome.compilerErrors(),
                    null,
                    null,
                    false,
                    outcome.warnings(),
                    outcome.success() ? "Compilation completed." : "Compilation failed."
            ));
        } catch (IllegalArgumentException | UnsafeCodeException ex) {
            return badRequest(ex.getMessage());
        } catch (Exception ex) {
            return serverError(ex);
        }
    }

    @PostMapping("/run")
    public ResponseEntity<JavaGoResponse> run(@RequestBody(required = false) JavaGoRequest request) {
        try {
            RunOutcome outcome = javaGoService.run(requiredCode(request), request == null ? "" : request.input());
            return ResponseEntity.ok(new JavaGoResponse(
                    outcome.success(),
                    outcome.transpiledCode(),
                    outcome.compilerErrors(),
                    outcome.runtimeOutput(),
                    outcome.runtimeErrors(),
                    outcome.timedOut(),
                    outcome.warnings(),
                    outcome.message()
            ));
        } catch (IllegalArgumentException | UnsafeCodeException ex) {
            return badRequest(ex.getMessage());
        } catch (Exception ex) {
            return serverError(ex);
        }
    }

    private String requiredCode(JavaGoRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw new IllegalArgumentException("Request body must include JavaGo code.");
        }
        return request.code();
    }

    private ResponseEntity<JavaGoResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new JavaGoResponse(false, null, null, null, null, false, List.of(), message));
    }

    private ResponseEntity<JavaGoResponse> serverError(Exception ex) {
        String message = ex.getMessage() == null ? "JavaGo backend error." : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new JavaGoResponse(false, null, null, null, null, false, List.of(), message));
    }
}
