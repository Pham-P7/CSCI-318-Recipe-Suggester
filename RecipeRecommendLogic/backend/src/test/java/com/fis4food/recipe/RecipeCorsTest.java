package com.fis4food.recipe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** HTTP CORS checks without any database request or real Token. */
public class RecipeCorsTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeCorsTest.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        String origin = "http://localhost:5173";
        try (var http = HttpClient.newHttpClient();
             var context = start(origin + ", https://frontend.example.test")) {
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            var response = send(http,base,"/api/health",origin,"GET",null);
            assert response.statusCode() == 200;
            allowed(response,origin);
            assert response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty();
            allowed(send(http,base,"/api/health","https://frontend.example.test","GET",null),"https://frontend.example.test");
            allowed(send(http,base,"/api/health",origin,"HEAD",null),origin);

            var preflight = send(http,base,"/api/recipes",origin,"OPTIONS","GET");
            assert preflight.statusCode() == 200 : "Valid GET preflight must succeed";
            allowed(preflight,origin);
            assert preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET");
            assert !preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST");
            assert preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase(java.util.Locale.ROOT).contains("content-type");
            denied(send(http,base,"/api/health","https://untrusted.example","GET",null));
            denied(send(http,base,"/api/recipes",origin,"OPTIONS","POST"));

            var invalid = send(http,base,"/api/recipes?limit=0",origin,"GET",null);
            assert invalid.statusCode() == 400;
            allowed(invalid,origin);
            var ordinary = send(http,base,"/api/health",null,"GET",null);
            assert ordinary.statusCode() == 200;
            assert ordinary.headers().firstValue("Access-Control-Allow-Origin").isEmpty();
        }
        try (var http = HttpClient.newHttpClient(); var context = start("")) {
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            var response = send(http,base,"/api/health",origin,"GET",null);
            assert response.headers().firstValue("Access-Control-Allow-Origin").isEmpty() : "Empty origin configuration must not allow all origins";
        }
        for (String invalid : List.of("*", "https://*.example.test", "https://example.test/path", "https://example.test?secret=x", "https://user:pass@example.test")) {
            try (var ignored = start(invalid)) {
                throw new AssertionError("Invalid origin configuration must fail startup: " + invalid);
            } catch (org.springframework.beans.BeansException expected) {
                // Reject an invalid allowlist instead of opening it to every origin.
            }
        }
        System.out.println("PASS: configurable CORS, GET/HEAD/preflight, denied origins/methods, error headers, empty and invalid configuration.");
    }

    private static ServletWebServerApplicationContext start(String origins) {
        return (ServletWebServerApplicationContext) new SpringApplication(RecipeBackendApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--recipe.d1.query-url=http://127.0.0.1:1/query",
                "--recipe.d1.api-token=test-token", "--recipe.cors.allowed-origins=" + origins,
                "--spring.main.banner-mode=off", "--logging.level.root=OFF");
    }

    private static HttpResponse<String> send(HttpClient http,String base,String path,String origin,String method,String requestedMethod) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5));
        if (origin != null) request.header("Origin",origin);
        if (requestedMethod != null) {
            request.header("Access-Control-Request-Method",requestedMethod);
            request.header("Access-Control-Request-Headers","accept, content-type");
        }
        return http.send(request.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void allowed(HttpResponse<?> response,String origin) {
        assert response.headers().firstValue("Access-Control-Allow-Origin").orElse("").equals(origin) : "Missing exact allowed origin";
    }
    private static void denied(HttpResponse<?> response) {
        assert response.statusCode() == 403 : "Disallowed CORS request must be rejected";
        assert response.headers().firstValue("Access-Control-Allow-Origin").isEmpty();
    }
}
