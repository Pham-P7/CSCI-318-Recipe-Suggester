package com.fis4food.recipe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.Arrays;

@Configuration
public class ApiCorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public ApiCorsConfiguration(@Value("${recipe.cors.allowed-origins:}") String configured) {
        origins = Arrays.stream(configured.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).distinct().toArray(String[]::new);

        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || origin.contains("*") || uri.getUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("CORS origins must be exact HTTP(S) origins without paths or credentials");
            }
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) return;
        // The port is part of the origin too: localhost:5173 and :3000 are different.
        registry.addMapping("/api/**").allowedOrigins(origins)
                .allowedMethods("GET", "HEAD").allowedHeaders("Accept", "Content-Type")
                .allowCredentials(false);
    }
}
