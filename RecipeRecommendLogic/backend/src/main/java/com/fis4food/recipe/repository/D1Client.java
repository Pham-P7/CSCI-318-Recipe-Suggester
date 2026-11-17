package com.fis4food.recipe.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fis4food.recipe.exception.D1AccessException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;

// D1 client is a client for the D1 database service.
// It allows executing read-only SQL queries against the D1 database using HTTP requests.
public class D1Client {
    private final URI queryUri;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public D1Client(URI queryUri, String token, HttpClient http, ObjectMapper mapper, Duration requestTimeout) {
        this.queryUri = Objects.requireNonNull(queryUri);
        this.token = Objects.requireNonNull(token);
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);

        if (token.isBlank() || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("D1 Token must be non-empty without whitespace");
        }

        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("D1 request timeout must be positive");
        }
    }


    public List<JsonNode> query(String sql, List<String> params) {

        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }

        requireReadOnly(sql);
        List<String> boundParams = List.copyOf(params);

        if (boundParams.size() > 100) {
            throw new IllegalArgumentException("D1 queries support at most 100 bound parameters");
        }

        try {
            String body = mapper.writeValueAsString(Map.of("sql", sql, "params", boundParams));

            // D1 uses POST for queries too. Our SQL is still read-only.
            HttpRequest request = HttpRequest.newBuilder(queryUri)
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            //check response from D1, if the status code is not 2xx, throw an exception
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new D1AccessException("D1 returned HTTP " + response.statusCode(), false);
            }


            // HTTP 200 is not enough; the JSON can still report a SQL error.
            //response a string, parse it to jsonNode, check the jsonNode
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body());

            //outermost checking
            if (root == null || !root.isObject() || !succeeded(root)) {
                throw new D1AccessException("D1 response did not report success", false);
            }

            //second level checking: whether the result field is an array with exactly one element
            JsonNode results = root.path("result");
            if (!results.isArray() || results.size() != 1) {
                throw new D1AccessException("Expected one D1 query result", false);
            }

            //third level checking: whether the result is a successful query
            JsonNode result = results.get(0);
            if (!result.isObject() || !succeeded(result)) {
                throw new D1AccessException("D1 SQL query did not succeed", false);
            }

            //fourth level checking: whether the result has a results field that is an array
            //this is the actual data returned by the query
            JsonNode rows = result.path("results");
            if (!rows.isArray()) {
                throw new D1AccessException("D1 response is missing its result rows", false);
            }



            var items = new ArrayList<JsonNode>();// receiving the rows from D1 and checking

            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    throw new D1AccessException("D1 returned an invalid result row", false);
                }
                items.add(row);
            }
            return List.copyOf(items);

        } catch (HttpTimeoutException ex) {
            throw new D1AccessException("D1 request timed out", true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new D1AccessException("D1 request was interrupted", false);
        } catch (IOException ex) {
            // Do not include response bodies, request headers or Token in exceptions.
            throw new D1AccessException("D1 network or JSON response failed", false);
        }
    }

    //excluded  SQL DML words
    private static final Set<String> WRITES = Set.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "ALTER", "DROP", "TRUNCATE",
            "VACUUM", "REINDEX", "ANALYZE", "ATTACH", "DETACH", "PRAGMA", "INTO",
            "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE"
    );
    private static final Set<String> SIDE_EFFECT_FUNCTIONS = Set.of(
            "LOAD_EXTENSION", "WRITEFILE", "EVAL", "FTS3_TOKENIZER"
    );


    // This only catches local mistakes. The Token still needs D1 Read permission.
    private static void requireReadOnly(String sql) {
        boolean started = false;
        boolean ended = false;
        for (int i = 0; i < sql.length();) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (sql.startsWith("--", i)) {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }

            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw readOnlyViolation();
                i = end + 2;
                continue;
            }

            if (ended) throw readOnlyViolation();
            if (ch == '\'' || ch == '\"' || ch == '`' || ch == '[') {
                if (!started) throw readOnlyViolation();
                char closing = ch == '[' ? ']' : ch;
                var quoted = new StringBuilder();
                boolean closed = false;
                i++;
                while (i < sql.length()) {
                    char current = sql.charAt(i++);
                    if (current == closing) {
                        if (ch != '[' && i < sql.length() && sql.charAt(i) == closing) {
                            quoted.append(closing);
                            i++;
                        } else { closed = true; break; }
                    } else quoted.append(current);
                }
                if (!closed || SIDE_EFFECT_FUNCTIONS.contains(quoted.toString().toUpperCase(Locale.ROOT))) {
                    throw readOnlyViolation();
                }
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int begin = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                String word = sql.substring(begin, i).toUpperCase(Locale.ROOT);
                if (!started && !word.equals("SELECT") && !word.equals("WITH")) throw readOnlyViolation();
                started = true;
                if (WRITES.contains(word) || SIDE_EFFECT_FUNCTIONS.contains(word) || word.startsWith("PRAGMA_")) {
                    throw readOnlyViolation();
                }
                continue;
            }
            if (!started) throw readOnlyViolation();
            if (ch == ';') ended = true;
            i++;
        }
        if (!started) throw readOnlyViolation();
    }

    private static IllegalArgumentException readOnlyViolation() {
        return new IllegalArgumentException("D1 client only permits a single read-only SELECT/WITH query");
    }

    // check success field
    private static boolean succeeded(JsonNode node) {
        JsonNode success = node.path("success");
        return success.isBoolean() && success.booleanValue();
    }
}
