package com.fis4food.recipe;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** The manual checker must reject broken pagination instead of announcing PASS. */
public class ApiSmokeCheckTest {
    public static void main(String[] args) throws Exception {
        if (!ApiSmokeCheckTest.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        rejects("missing second page", "[{\"recipeId\":1},{\"recipeId\":2}]", "[]", false);
        rejects("partial page claiming more", "[{\"recipeId\":1},{\"recipeId\":2}]", "[{\"recipeId\":3}]", true);
        rejects("descending IDs", "[{\"recipeId\":2},{\"recipeId\":1}]", "[{\"recipeId\":3}]", false);
        rejects("invalid ID", "[{\"recipeId\":0},{\"recipeId\":2}]", "[{\"recipeId\":3}]", false);
        System.out.println("PASS: manual smoke checker rejects inconsistent pagination and invalid recipe IDs.");
    }

    private static void rejects(String scenario, String firstItems, String nextItems, boolean nextHasMore) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            int status = 200;
            String body;
            if (path.equals("/api/health")) body = "{\"status\":\"UP\"}";
            else if (path.equals("/api/recipes/2")) body = "{\"recipeId\":2,\"ingredients\":[]}";
            else if (path.equals("/api/recipes/99999999")) { status = 404; body = "{\"message\":\"Not found\"}"; }
            else if ("limit=0".equals(query)) { status = 400; body = "{\"message\":\"Invalid limit\"}"; }
            else {
                boolean next = query != null && query.contains("offset=2");
                body = "{\"items\":" + (next ? nextItems : firstItems)
                        + ",\"limit\":2,\"offset\":" + (next ? 2 : 0)
                        + ",\"hasMore\":" + (!next || nextHasMore) + "}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            boolean rejected = false;
            try { ApiSmokeCheck.main(new String[]{"http://127.0.0.1:" + server.getAddress().getPort()}); }
            catch (AssertionError expected) { rejected = true; }
            assert rejected : "Smoke checker accepted " + scenario;
        } finally { server.stop(0); }
    }
}
