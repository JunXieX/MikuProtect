package dev.junxiex.mikuprotect.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Optional read-only HTTP API. Disabled by default; requires a non-empty API key.
 *
 * <p>Handlers run on virtual threads, so blocking on the lookup future is safe and never touches
 * the server tick threads.</p>
 */
public final class WebService {

    private final MikuProtectPlugin plugin;
    private final MikuConfig config;
    private final RateLimiter rateLimiter;
    private HttpServer server;

    public WebService(MikuProtectPlugin plugin, MikuConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.rateLimiter = new RateLimiter(config.webRateLimitPerMinute, config.webRateLimitBurst);
    }

    public boolean start() {
        if (!config.webEnabled) {
            return false;
        }
        if (config.webApiKey == null || config.webApiKey.isBlank()) {
            plugin.getLogger().warning("web.enabled=true but web.api-key is empty; web API not started");
            return false;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(config.webBind, config.webPort), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/v1/status", exchange -> handle(exchange, this::status));
            server.createContext("/api/v1/activities", exchange -> handle(exchange, this::activities));
            server.start();
            plugin.getLogger().info("Web API listening on " + config.webBind + ":" + config.webPort);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Web API failed to start: " + exception.getMessage());
            server = null;
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange exchange, Handler handler) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.equals("Bearer " + config.webApiKey)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String remote = exchange.getRemoteAddress() == null
                    || exchange.getRemoteAddress().getAddress() == null ? "unknown"
                    : exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!rateLimiter.allow(remote)) {
                exchange.getResponseHeaders().set("Retry-After", "60");
                send(exchange, 429, "{\"error\":\"rate limited\"}");
                return;
            }
            send(exchange, 200, handler.run(exchange));
        } catch (Exception exception) {
            send(exchange, 500, "{\"error\":\"" + escape(exception.getMessage()) + "\"}");
        } finally {
            exchange.close();
        }
    }

    private String status(HttpExchange exchange) {
        return "{\"storage\":\"" + config.storageType.name().toLowerCase(Locale.ROOT) + "\","
                + "\"queue\":" + plugin.recording().size() + ","
                + "\"capacity\":" + plugin.recording().capacity() + ","
                + "\"written\":" + plugin.recording().written() + ","
                + "\"dropped\":" + plugin.recording().dropped() + "}";
    }

    private String activities(HttpExchange exchange) throws Exception {
        Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
        LookupQuery query = LookupQuery.all()
                .world(params.get("world"))
                .user(params.get("user"))
                .material(params.get("material"))
                .limit(Math.min(config.webMaxResults, parse(params.get("limit"), 20)))
                .page(Math.max(1, parse(params.get("page"), 1)))
                .since(parseLong(params.get("since"), 0L));
        List<StoredActivity> rows = plugin.lookup().lookup(query).get(10, java.util.concurrent.TimeUnit.SECONDS);
        StringBuilder json = new StringBuilder(rows.size() * 160 + 2);
        json.append('[');
        for (int index = 0; index < rows.size(); index++) {
            StoredActivity row = rows.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(row.id())
                    .append(",\"time\":").append(row.time())
                    .append(",\"world\":\"").append(escape(row.worldName())).append("\"")
                    .append(",\"user\":\"").append(escape(row.userName())).append("\"")
                    .append(",\"action\":\"").append(row.action() == null ? "" : row.action().key()).append("\"")
                    .append(",\"x\":").append(row.x()).append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"material\":\"").append(escape(row.material())).append("\"")
                    .append(",\"rolledBack\":").append(row.rolledBack()).append('}');
        }
        return json.append(']').toString();
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (String token : raw.split("&")) {
            int split = token.indexOf('=');
            if (split > 0) {
                try {
                    result.put(java.net.URLDecoder.decode(token.substring(0, split), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(token.substring(split + 1), StandardCharsets.UTF_8));
                } catch (RuntimeException ignored) {
                    // skip malformed parameter
                }
            }
        }
        return result;
    }

    private static int parse(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    @FunctionalInterface
    private interface Handler {
        String run(HttpExchange exchange) throws Exception;
    }
}
