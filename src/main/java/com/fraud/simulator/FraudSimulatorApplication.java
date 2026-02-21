package com.fraud.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class FraudSimulatorApplication {
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_RATE_LIMIT = 120;
    private static final long LATENCY_BUDGET_MS = 200;

    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final AlertStore alerts;

    private FraudSimulatorApplication(ObjectMapper mapper, RateLimiter rateLimiter, AlertStore alerts) {
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.alerts = alerts;
    }

    public static void main(String[] args) throws IOException {
        int port = readIntEnv("PORT", DEFAULT_PORT);
        int rateLimit = readIntEnv("RATE_LIMIT_PER_MIN", DEFAULT_RATE_LIMIT);

        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        RateLimiter limiter = new RateLimiter(rateLimit, Duration.ofMinutes(1));
        AlertStore alerts = new AlertStore();
        FraudSimulatorApplication app = new FraudSimulatorApplication(mapper, limiter, alerts);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/health", app::handleHealth);
        server.createContext("/api/v1/transactions", app::handleTransaction);
        server.createContext("/api/v1/alerts", app::handleAlerts);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!allow(exchange)) {
            return;
        }
        writeJson(exchange, 200, new StatusResponse("ok"));
        log(exchange, new LogEntry("health check", newTraceId(), "", "", 200, LATENCY_BUDGET_MS, 0, false));
    }

    private void handleTransaction(HttpExchange exchange) throws IOException {
        Instant start = Instant.now();
        String traceId = newTraceId();

        if (!allow(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, new ErrorResponse("method not allowed"));
            log(exchange, new LogEntry("method not allowed", traceId, "", "", 405, LATENCY_BUDGET_MS, 0, false));
            return;
        }

        TransactionRequest request;
        try {
            request = mapper.readValue(exchange.getRequestBody(), TransactionRequest.class);
        } catch (JsonProcessingException ex) {
            writeJson(exchange, 400, new ErrorResponse("invalid json"));
            log(exchange, new LogEntry("invalid request", traceId, "", "", 400, LATENCY_BUDGET_MS, 0, false));
            return;
        }

        List<String> validationErrors = validate(request);
        if (!validationErrors.isEmpty()) {
            writeJson(exchange, 400, new ErrorResponse(String.join("; ", validationErrors)));
            log(exchange, new LogEntry("validation failed", traceId, request.transactionId, "", 400, LATENCY_BUDGET_MS, 0, false));
            return;
        }

        ScoringResult scoring = score(request);
        if ("block".equals(scoring.decision)) {
            alerts.add(new Alert(request.transactionId, scoring.score, scoring.rules));
        }

        TransactionResponse response = new TransactionResponse(
            request.transactionId,
            traceId,
            scoring.score,
            scoring.decision,
            scoring.rules
        );
        writeJson(exchange, 200, response);

        long durationMs = Duration.between(start, Instant.now()).toMillis();
        log(exchange, new LogEntry(
            "transaction scored",
            traceId,
            request.transactionId,
            scoring.decision,
            200,
            LATENCY_BUDGET_MS,
            durationMs,
            durationMs > LATENCY_BUDGET_MS
        ));
    }

    private void handleAlerts(HttpExchange exchange) throws IOException {
        if (!allow(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, new ErrorResponse("method not allowed"));
            return;
        }
        writeJson(exchange, 200, new AlertsResponse(alerts.list()));
    }

    private boolean allow(HttpExchange exchange) throws IOException {
        String key = clientIp(exchange);
        if (!rateLimiter.allow(key)) {
            writeJson(exchange, 429, new ErrorResponse("rate limit exceeded"));
            return false;
        }
        return true;
    }

    private void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] data = mapper.writeValueAsBytes(payload);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void log(HttpExchange exchange, LogEntry entry) throws IOException {
        byte[] data = mapper.writeValueAsBytes(entry);
        System.out.println(new String(data));
    }

    private List<String> validate(TransactionRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.transactionId == null || request.transactionId.isBlank()) {
            errors.add("transactionId is required");
        }
        if (request.accountId == null || request.accountId.isBlank()) {
            errors.add("accountId is required");
        }
        if (request.amount <= 0) {
            errors.add("amount must be > 0");
        }
        if (request.currency == null || request.currency.isBlank()) {
            errors.add("currency is required");
        }
        return errors;
    }

    private ScoringResult score(TransactionRequest request) {
        double score = 10;
        List<String> rules = new ArrayList<>();

        if (request.amount >= 1000) {
            score += 70;
            rules.add("high-amount");
        }
        if ("card-not-present".equalsIgnoreCase(request.channel)) {
            score += 30;
            rules.add("card-not-present");
        }

        String decision = score >= 80 ? "block" : "allow";
        if (rules.isEmpty()) {
            rules.add("baseline");
        }
        return new ScoringResult(score, decision, rules);
    }

    private String clientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    private static int readIntEnv(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record StatusResponse(String status) {}

    private record ErrorResponse(String error) {}

    private record TransactionRequest(
        String transactionId,
        String accountId,
        double amount,
        String currency,
        String channel
    ) {}

    private record TransactionResponse(
        String transactionId,
        String traceId,
        double score,
        String decision,
        List<String> rules
    ) {}

    private record Alert(String transactionId, double score, List<String> rules) {}

    private record AlertsResponse(List<Alert> alerts) {}

    private record LogEntry(
        String message,
        String traceId,
        String transactionId,
        String decision,
        int status,
        long budgetMs,
        long durationMs,
        boolean budgetExceeded
    ) {}

    private record ScoringResult(double score, String decision, List<String> rules) {}

    private static final class AlertStore {
        private final CopyOnWriteArrayList<Alert> alerts = new CopyOnWriteArrayList<>();

        private void add(Alert alert) {
            alerts.add(alert);
        }

        private List<Alert> list() {
            return List.copyOf(alerts);
        }
    }

    private static final class RateLimiter {
        private final int maxRequests;
        private final Duration window;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

        private RateLimiter(int maxRequests, Duration window) {
            this.maxRequests = maxRequests;
            this.window = window;
        }

        private boolean allow(String key) {
            Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
            synchronized (bucket) {
                Instant now = Instant.now();
                if (bucket.windowStart == null || Duration.between(bucket.windowStart, now).compareTo(window) >= 0) {
                    bucket.windowStart = now;
                    bucket.count = 0;
                }
                if (bucket.count >= maxRequests) {
                    return false;
                }
                bucket.count++;
                return true;
            }
        }

        private static final class Bucket {
            private Instant windowStart;
            private int count;
        }
    }
}
