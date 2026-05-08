package javago.util;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

@Component
public class WorkspaceManager {
    public Path createWorkspace() throws IOException {
        return Files.createTempDirectory("javago-" + UUID.randomUUID() + "-");
    }

    public void cleanup(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }

        try {
            Files.walk(workspace)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
