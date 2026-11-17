package com.fis4food.recipe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.repository.D1Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class D1Configuration {

    @Bean(destroyMethod = "close")
    public HttpClient d1HttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean

    public D1Client d1Client(
            HttpClient d1HttpClient, ObjectMapper mapper,
            @Value("${recipe.d1.query-url}") String queryUrl,
            @Value("${recipe.d1.api-token}") String token,
            @Value("${recipe.d1.request-timeout:PT30S}") Duration timeout) {

        URI uri = URI.create(queryUrl);
        boolean https = "https".equals(uri.getScheme());
        boolean localTest = "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));

        if ((!https && !localTest) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("D1 query URL must use HTTPS or local HTTP for testing");
        }

        return new D1Client(uri, token, d1HttpClient, mapper, timeout);
    }
}
