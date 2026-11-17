package com.fis4food.recipe.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.exception.D1AccessException;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

public class D1ClientTest {
    private record Reply(int status, String body, long delayMillis) {}

    public static void main(String[] args) throws Exception {
        if (!D1ClientTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea");
        }
        var mapper = new ObjectMapper();
        var reply = new AtomicReference<>(new Reply(200, "", 0));
        var method = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/query", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                method.set(exchange.getRequestMethod());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Reply current = reply.get();
                if (current.delayMillis() > 0) {
                    try {
                        Thread.sleep(current.delayMillis());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(current.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException expectedAfterClientTimeout) {
                // A timed-out client can close the socket before this test server replies.
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/query");
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var client = new D1Client(uri, "test-secret", http, mapper, Duration.ofSeconds(2));
            String sql = "SELECT recipeId, name, recipeServing FROM recipes WHERE name = ?";
            String name = "Liu's 汤; % _\n";
            reply.set(new Reply(200, """
                    {"success":true,"errors":[],"messages":[],"result":[{
                    "success":true,"meta":{"rows_read":1},"results":[
                    {"recipeId":2,"name":"米饭","recipeServing":null}]}]}
                    """, 0));
            var rows = client.query(sql, List.of(name));
            assert rows.size() == 1 : "A successful D1 row must be returned";
            assert rows.getFirst().path("recipeId").asInt() == 2;
            assert "米饭".equals(rows.getFirst().path("name").asText());
            assert rows.getFirst().path("recipeServing").isNull() : "JSON null must survive";
            assert "POST".equals(method.get());
            assert "Bearer test-secret".equals(authorization.get());
            assert "application/json".equals(contentType.get());
            var sent = mapper.readTree(requestBody.get());
            assert sql.equals(sent.path("sql").asText()) : "SQL must not contain interpolated values";
            assert sent.path("params").size() == 1;
            assert name.equals(sent.path("params").get(0).asText()) : "Parameter characters must survive JSON encoding";

            reply.set(new Reply(200, "{\"success\":true,\"result\":[{\"success\":true,\"results\":[]}]}", 0));
            assert client.query("SELECT 1 WHERE 0", List.of()).isEmpty();

            // A rejected statement must never reach HTTP, even with a writable Token.
            String[] unsafeSql = {
                    "INSERT INTO recipes(name) VALUES (?)",
                    "UPDATE recipes SET name = ?", "DELETE FROM recipes",
                    "CREATE TABLE accidental(id INTEGER)", "ALTER TABLE recipes ADD COLUMN x TEXT",
                    "DROP TABLE recipes", "REPLACE INTO recipes(recipeId) VALUES (1)",
                    "PRAGMA writable_schema=ON", "VACUUM", "ATTACH DATABASE 'backup.db' AS backup",
                    "BEGIN", "COMMIT", "ANALYZE recipes", "REINDEX",
                    "SELECT 1; DELETE FROM recipes", "SELECT 1; SELECT 2",
                    "WITH c AS (SELECT 1) DELETE FROM recipes",
                    "WITH c AS (SELECT 1) UPDATE recipes SET name='oops'",
                    "/* comment */ insert INTO recipes(name) VALUES ('oops')",
                    "SELECT 1; -- comment\n DROP TABLE recipes",
                    "SELECT load_extension('x')", "SELECT writefile('x','y')",
                    "SELECT \"writefile\"('x','y')", "SELECT [writefile]('x','y')",
                    "SELECT `writefile`('x','y')", "SELECT 'writefile'('x','y')",
                    "SELECT eval('DELETE FROM recipes')",
                    "SELECT 'unterminated", "SELECT 1 /* unterminated", ";SELECT 1"
            };
            for (String unsafe : unsafeSql) {
                int before = requests.get();
                try {
                    client.query(unsafe, List.of("rice"));
                    throw new AssertionError("Unsafe SQL must be rejected before HTTP: " + unsafe);
                } catch (IllegalArgumentException expected) {
                    assert requests.get() == before : "Rejected SQL reached the database";
                    assert !expected.getMessage().contains("test-secret");
                }
            }
            for (String read : new String[]{
                    " -- leading comment\n select 1; /* trailing comment */",
                    "SELECT 'DELETE; INSERT; DROP' AS text",
                    "SELECT 'Liu''s; DELETE FROM recipes' AS text",
                    "SELECT \"name\" FROM recipes",
                    "WITH c AS (SELECT ? AS value) SELECT value FROM c",
                    "WITH c AS (SELECT CASE WHEN 1 THEN 'UPDATE' ELSE 'DELETE' END AS value) SELECT value FROM c"
            }) {
                int before = requests.get();
                assert client.query(read, List.of()).isEmpty();
                assert requests.get() == before + 1 : "Read-only SQL must still reach HTTP";
            }

            String[] invalidBodies = {
                    "{\"success\":false,\"errors\":[{\"message\":\"test-secret\"}],\"result\":null}",
                    "{\"success\":true,\"result\":[{\"success\":false,\"results\":[]}]}",
                    "{\"success\":true,\"result\":[]}",
                    "{\"success\":true}",
                    "{\"success\":true,\"result\":[{\"success\":true}]}",
                    "{\"success\":true,\"result\":[{\"success\":true,\"results\":null}]}",
                    "{\"success\":true,\"result\":[{\"success\":true,\"results\":[null]}]}",
                    "{\"success\":\"true\",\"result\":[{\"success\":true,\"results\":[]}]}",
                    "{\"success\":true,\"result\":[{\"success\":\"true\",\"results\":[]}]}",
                    "{\"success\":true,\"result\":[{\"success\":true,\"results\":[]},{\"success\":true,\"results\":[]}]}",
                    "not JSON", "null",
                    "{\"success\":true,\"result\":[{\"success\":true,\"results\":[]}]} trailing"
            };
            for (String body : invalidBodies) {
                reply.set(new Reply(200, body, 0));
                expectFailure(client, false);
            }
            for (int status : new int[]{401, 403, 429, 500}) {
                reply.set(new Reply(status, "test-secret upstream body", 0));
                expectFailure(client, false);
            }
            try {
                client.query("SELECT 1", java.util.Collections.nCopies(101, "rice"));
                throw new AssertionError("Too many bound parameters must be rejected locally");
            } catch (IllegalArgumentException expected) {
            }
            Thread.currentThread().interrupt();
            try {
                expectFailure(client, false);
                assert Thread.currentThread().isInterrupted() : "Preserve caller interruption";
            } finally {
                Thread.interrupted();
            }
            reply.set(new Reply(200, "{}", 700));
            var impatient = new D1Client(uri, "test-secret", http, mapper, Duration.ofMillis(100));
            expectFailure(impatient, true);
            server.stop(0);
            expectFailure(client, false);
            System.out.println("PASS: read-only SQL rejection before HTTP, D1 request binding, UTF-8, NULL, empty results, protocol failures, HTTP errors, timeout and connection failure.");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void expectFailure(D1Client client, boolean timeout) {
        try {
            client.query("SELECT 1", List.of());
            throw new AssertionError("Failed D1 requests must not become successful empty results");
        } catch (D1AccessException ex) {
            assert ex.isTimeout() == timeout : "Timeout classification differs";
            assert !ex.getMessage().contains("test-secret") : "Never expose response contents or Token";
        }
    }
}
