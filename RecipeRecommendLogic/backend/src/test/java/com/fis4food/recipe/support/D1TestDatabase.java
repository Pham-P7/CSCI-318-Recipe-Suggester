package com.fis4food.recipe.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.repository.D1Client;
import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Test-only D1 HTTP boundary backed by real SQLite SQL execution. */
public final class D1TestDatabase implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int status = 200;
    private volatile String bodyOverride;
    private volatile long delayMillis;

    public D1TestDatabase(Path database) {
        this(new DriverManagerDataSource("jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro"));
    }

    public D1TestDatabase(DataSource source) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.setExecutor(executor);
        server.createContext("/query", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                long delay = delayMillis;
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                int responseStatus = status;
                String response = bodyOverride;
                if (!"POST".equals(exchange.getRequestMethod())
                        || !"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    responseStatus = 403;
                    response = "{\"success\":false,\"errors\":[{\"message\":\"Wrong test credentials\"}]}";
                }
                if (response == null) {
                    try {
                        var payload = mapper.readTree(request);
                        var rows = new ArrayList<Map<String, Object>>();
                        try (var connection = source.getConnection();
                             var statement = connection.prepareStatement(payload.path("sql").asText())) {
                            var params = payload.path("params");
                            for (int i = 0; i < params.size(); i++) {
                                if (!params.get(i).isTextual()) throw new SQLException("Expected a string parameter");
                                statement.setString(i + 1, params.get(i).textValue());
                            }
                            try (var result = statement.executeQuery()) {
                                var metadata = result.getMetaData();
                                while (result.next()) {
                                    var row = new LinkedHashMap<String, Object>();
                                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                                        Object value = result.getObject(i);
                                        // JSON has no native NaN/Infinity; expose these fixture values as null.
                                        if (value instanceof Double number && !Double.isFinite(number)) value = null;
                                        row.put(metadata.getColumnLabel(i), value);
                                    }
                                    rows.add(row);
                                }
                            }
                        }
                        response = mapper.writeValueAsString(Map.of("success", true, "errors", List.of(),
                                "result", List.of(Map.of("success", true, "results", rows))));
                    } catch (SQLException ex) {
                        responseStatus = 400;
                        response = "{\"success\":false,\"errors\":[{\"message\":\"Test SQL failed\"}]}";
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException expectedWhenClientTimesOut) {
                // Timed-out clients can close the socket before the reply is written.
            }
        });
        server.start();
    }

    /** Convert hand-written legacy fixtures to the inspected shared schema; local tests only. */
    public static D1TestDatabase existingSchema(Path database) {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + database);
        prepareExistingSchema(source);
        return new D1TestDatabase(database);
    }

    public static void prepareExistingSchema(DataSource source) {
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            var columns = new java.util.HashSet<String>();
            try (var rows = sql.executeQuery("PRAGMA table_info(recipes)")) {
                while (rows.next()) columns.add(rows.getString("name"));
            }
            if (!columns.contains("recipeIngredientParts")) {
                sql.execute("ALTER TABLE recipes ADD COLUMN recipeIngredientParts TEXT");
                boolean ingredients;
                try (var rows = sql.executeQuery("SELECT 1 FROM sqlite_schema WHERE type='table' AND name='recipe_ingredients'")) {
                    ingredients = rows.next();
                }
                var ids = new ArrayList<Integer>();
                try (var rows = sql.executeQuery("SELECT recipeId FROM recipes")) {
                    while (rows.next()) ids.add(rows.getInt(1));
                }
                var mapper = new ObjectMapper();
                try (var update = connection.prepareStatement("UPDATE recipes SET recipeIngredientParts=? WHERE recipeId=?")) {
                    for (int id : ids) {
                        var names = new ArrayList<String>();
                        if (ingredients) {
                            try (var select = connection.prepareStatement("SELECT ingredient_name FROM recipe_ingredients WHERE recipeId=? ORDER BY position")) {
                                select.setInt(1,id);
                                try (var rows = select.executeQuery()) {
                                    while (rows.next()) {
                                        String name = rows.getString(1);
                                        names.add(name == null ? "NA" : mapper.writeValueAsString(name));
                                    }
                                }
                            }
                        }
                        update.setString(1, names.isEmpty() ? "character(0)" : "c(" + String.join(", ",names) + ")");
                        update.setInt(2,id);
                        update.executeUpdate();
                    }
                }
            }
            sql.execute("DROP TABLE IF EXISTS recipe_ingredients");
            for (String column : java.util.List.of("name_match_text", "category_match_name")) {
                if (columns.contains(column)) sql.execute("ALTER TABLE recipes DROP COLUMN " + column);
            }
        } catch (java.sql.SQLException | java.io.IOException error) {
            throw new IllegalStateException("Cannot prepare existing-schema test fixture", error);
        }
    }

    public URI queryUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/query");
    }

    public D1Client client(ObjectMapper mapper) {
        return new D1Client(queryUri(), "test-token", http, mapper, Duration.ofSeconds(5));
    }

    public void fail(int status, String body) {
        this.status = status;
        this.bodyOverride = body;
    }

    public void delay(long milliseconds) {
        this.delayMillis = milliseconds;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        http.close();
    }
}
