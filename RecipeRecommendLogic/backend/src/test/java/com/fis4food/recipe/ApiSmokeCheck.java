package com.fis4food.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Manual GET-only check of a running Spring service; never reads a D1 Token. */
public class ApiSmokeCheck {
    public static void main(String[] args) throws Exception {
        if (!ApiSmokeCheck.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        if (args.length > 1) throw new IllegalArgumentException("Usage: ApiSmokeCheck [http://localhost:8080]");
        String base = args.length == 0 ? "http://localhost:8080" : args[0].replaceAll("/+$", "");
        URI uri = URI.create(base);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("API base must be an HTTP(S) origin without credentials or a path");
        }
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var mapper = new ObjectMapper();
            assert get(http,mapper,base,"/api/health",200).path("status").asText().equals("UP");
            var detail = get(http,mapper,base,"/api/recipes/2",200);
            assert detail.path("recipeId").asInt() == 2;
            assert detail.path("ingredients").isArray();
            for (var ingredient : detail.path("ingredients")) {
                assert ingredient.path("quantity").isNull() : "Unverified quantities must stay null";
            }
            var first = get(http,mapper,base,"/api/recipes?limit=2",200);
            var next = get(http,mapper,base,"/api/recipes?limit=2&offset=2",200);
            int previousId = 0;
            for (var page : new JsonNode[]{first,next}) {
                assert page.path("items").isArray() && page.path("items").size() <= 2;
                assert page.path("limit").asInt() == 2 && page.path("hasMore").isBoolean();
                assert page.path("items").size() == 2 || !page.path("hasMore").asBoolean()
                        : "A partial page cannot claim another page";
                for (var item : page.path("items")) {
                    var id = item.path("recipeId");
                    assert id.isIntegralNumber() && id.canConvertToInt() && id.intValue() > previousId
                            : "Recipe IDs must be positive and strictly increasing across pages";
                    previousId = id.intValue();
                }
            }
            assert first.path("items").size() == 2 && first.path("offset").asInt() == 0;
            assert next.path("offset").asInt() == 2;
            assert first.path("hasMore").asBoolean() == !next.path("items").isEmpty()
                    : "hasMore must agree with the following page";
            assert get(http,mapper,base,"/api/recipes?limit=0",400).path("message").isTextual();
            assert get(http,mapper,base,"/api/recipes/99999999",404).path("message").isTextual();
            System.out.println("PASS: six GET requests checked health, detail, two pages and 400/404. No writes or retries.");
            System.out.println("Timings above measure HTTP round trips, not isolated D1 SQL time. Database ID must be confirmed from startup configuration.");
        }
    }

    private static JsonNode get(HttpClient http,ObjectMapper mapper,String base,String path,int expected) throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base+path))
                .timeout(Duration.ofSeconds(40)).GET().build(),HttpResponse.BodyHandlers.ofString());
        double millis = (System.nanoTime()-start)/1_000_000.0;
        System.out.printf(java.util.Locale.ROOT,"GET %s -> %d (%.1f ms)%n",path,response.statusCode(),millis);
        assert response.statusCode() == expected : "Unexpected HTTP status; stop rather than retry against the shared database";
        return mapper.readTree(response.body());
    }
}
