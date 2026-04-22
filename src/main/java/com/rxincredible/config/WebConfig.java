package com.rxincredible.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload.directory}")
    private String uploadDirectory;

    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:5180}")
    private String allowedOrigins;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path configuredPath = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path uploadsRoot = configuredPath.getFileName() != null
                && "documents".equalsIgnoreCase(configuredPath.getFileName().toString())
                && configuredPath.getParent() != null
                ? configuredPath.getParent()
                : configuredPath;
        
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadsRoot.toUri().toString());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
