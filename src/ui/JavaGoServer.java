package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.Transpiler;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class JavaGoServer {
    private static final int PORT = 8080;

    private final Transpiler transpiler = new Transpiler();
    private final Map<String, ProcessSession> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        new JavaGoServer().start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", this::handleHome);
        server.createContext("/transpile", this::handleTranspile);
        server.createContext("/run", this::handleRun);
        server.createContext("/stream", this::handleStream);
        server.createContext("/input", this::handleInput);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("JavaGo server running at http://localhost:" + PORT + "/");
    }

    private void handleHome(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "Method not allowed", "text/plain");
            return;
        }

        String frontend = loadFrontendHtml();
        if (frontend == null) {
            send(exchange, 404, "javago-frontend.html not found", "text/plain; charset=utf-8");
            return;
        }

        send(exchange, 200, frontend, "text/html; charset=utf-8");
    }

    private void handleTranspile(HttpExchange exchange) throws IOException {
        if (handleOptions(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "Method not allowed", "text/plain");
            return;
        }

        try {
            String source = readBody(exchange);
            Transpiler.Result result = transpiler.transpile(source, "Test");
            send(exchange, 200, result.getJavaCode(), "text/plain; charset=utf-8");
        } catch (Exception ex) {
            send(exchange, 500, message(ex), "text/plain; charset=utf-8");
        }
    }

    private void handleRun(HttpExchange exchange) throws IOException {
        if (handleOptions(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "Method not allowed", "text/plain");
            return;
        }

        try {
            String source = readBody(exchange);
            Transpiler.Result transpiled = transpiler.transpile(source, "Test");
            Path workspace = Files.createTempDirectory("javago-terminal-");
            Path javaFile = workspace.resolve(transpiled.getClassName() + ".java");
            Files.writeString(javaFile, transpiled.getJavaCode(), StandardCharsets.UTF_8);

            ProcessResult compile = runProcess(new ProcessBuilder("javac", javaFile.getFileName().toString())
                    .directory(workspace.toFile()));
            if (compile.exitCode != 0) {
                send(exchange, 400, firstNonBlank(compile.stderr, compile.stdout), "text/plain; charset=utf-8");
                return;
            }

            Process process = new ProcessBuilder("java", "-cp", ".", transpiled.getClassName())
                    .directory(workspace.toFile())
                    .start();

            String sessionId = UUID.randomUUID().toString();
            ProcessSession session = new ProcessSession(
                    process,
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))
            );
            sessions.put(sessionId, session);

            for (String warning : transpiled.getWarnings()) {
                session.offer("output", warning + System.lineSeparator());
            }
            startReader(session, process.getInputStream(), "output");
            startReader(session, process.getErrorStream(), "output");
            startWatcher(sessionId, session);

            send(exchange, 200, sessionId, "text/plain; charset=utf-8");
        } catch (Exception ex) {
            send(exchange, 500, message(ex), "text/plain; charset=utf-8");
        }
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (handleOptions(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "Method not allowed", "text/plain");
            return;
        }

        String id = queryParam(exchange, "id");
        ProcessSession session = sessions.get(id);
        if (session == null) {
            send(exchange, 404, "Session not found", "text/plain");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        addCors(exchange);
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream stream = exchange.getResponseBody()) {
            while (true) {
                StreamMessage message = session.messages.poll(30, TimeUnit.SECONDS);
                if (message == null) {
                    writeSse(stream, "ping", "");
                    continue;
                }

                writeSse(stream, message.event, message.data);
                if ("done".equals(message.event)) {
                    break;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleInput(HttpExchange exchange) throws IOException {
        if (handleOptions(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "Method not allowed", "text/plain");
            return;
        }

        String id = queryParam(exchange, "id");
        ProcessSession session = sessions.get(id);
        if (session == null || !session.process.isAlive()) {
            send(exchange, 410, "Process is not running", "text/plain");
            return;
        }

        try {
            String inputLine = readBody(exchange);
            session.writer.write(inputLine);
            session.writer.newLine();
            session.writer.flush();
            send(exchange, 200, "", "text/plain; charset=utf-8");
        } catch (IOException ex) {
            send(exchange, 500, message(ex), "text/plain; charset=utf-8");
        }
    }

    private void startReader(ProcessSession session, InputStream input, String event) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[256];
            int read;
            try {
                while ((read = input.read(buffer)) != -1) {
                    session.offer(event, new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ex) {
                session.offer("output", ex.getMessage() + System.lineSeparator());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void startWatcher(String sessionId, ProcessSession session) {
        Thread thread = new Thread(() -> {
            try {
                int exitCode = session.process.waitFor();
                closeQuietly(session.writer);
                session.offer("done", System.lineSeparator() + "[process exited with code " + exitCode + "]" + System.lineSeparator());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                session.offer("done", System.lineSeparator() + "[process interrupted]" + System.lineSeparator());
            } finally {
                sessions.remove(sessionId);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private ProcessResult runProcess(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder.start();
        process.getOutputStream().close();

        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        stdout.start();
        stderr.start();

        int exitCode = process.waitFor();
        stdout.join();
        stderr.join();
        return new ProcessResult(stdout.text(), stderr.text(), exitCode);
    }

    private void writeSse(OutputStream stream, String event, String data) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("event: ").append(event).append('\n');
        String normalized = data == null ? "" : data.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            builder.append("data: ").append(line).append('\n');
        }
        builder.append('\n');
        stream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String loadFrontendHtml() throws IOException {
        try (InputStream resource = JavaGoServer.class.getResourceAsStream("/ui/javago-frontend.html")) {
            if (resource != null) {
                return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        for (Path candidate : frontendCandidates()) {
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    private List<Path> frontendCandidates() {
        return List.of(
                Path.of("src", "ui", "javago-frontend.html"),
                Path.of("ui", "javago-frontend.html"),
                Path.of("javago-frontend.html"),
                Paths.get("").toAbsolutePath().resolve("src").resolve("ui").resolve("javago-frontend.html"),
                Paths.get("").toAbsolutePath().resolve("ui").resolve("javago-frontend.html")
        );
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        String prefix = name + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix)) {
                return URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private boolean handleOptions(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }
        addCors(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private void send(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        addCors(exchange);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private void closeQuietly(BufferedWriter writer) {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private String message(Exception ex) {
        return ex.getMessage() == null ? "JavaGo backend error" : ex.getMessage();
    }

    private static final class ProcessSession {
        private final Process process;
        private final BufferedWriter writer;
        private final BlockingQueue<StreamMessage> messages = new LinkedBlockingQueue<>();

        private ProcessSession(Process process, BufferedWriter writer) {
            this.process = process;
            this.writer = writer;
        }

        private void offer(String event, String data) {
            messages.offer(new StreamMessage(event, data));
        }
    }

    private static final class StreamMessage {
        private final String event;
        private final String data;

        private StreamMessage(String event, String data) {
            this.event = event;
            this.data = data;
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
