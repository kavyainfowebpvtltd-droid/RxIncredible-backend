package com.rxincredible.config;

import org.springframework.http.HttpMethod;
import com.rxincredible.service.CustomUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.AuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

   @Value("${cors.allowed-origins:http://97.74.87.179:3000}")
    private String allowedOrigins;

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(CustomUserDetailsService customUserDetailsService,
            JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.customUserDetailsService = customUserDetailsService;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(customUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // Custom authentication entry point to return proper JSON error responses
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.core.AuthenticationException authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            Map<String, String> error = new HashMap<>();
            error.put("error", "Unauthorized");
            error.put("message", "Authentication required. Please login.");
            response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(error));
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {
                        })
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .authorizeHttpRequests(auth -> {
                    // Public endpoints - authentication not required
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers("/api/auth/**").permitAll();
                    auth.requestMatchers("/api/services/**").permitAll();
                    auth.requestMatchers("/api/users/register").permitAll();
                    auth.requestMatchers("/api/users/register-direct").permitAll();
                    auth.requestMatchers("/api/users/pending").permitAll();
                    auth.requestMatchers("/api/users/verify-email").permitAll();
                    auth.requestMatchers("/api/users/resend-verification").permitAll();
                    auth.requestMatchers("/api/users/verify-otp").permitAll();
                    auth.requestMatchers("/api/users/resend-otp").permitAll();
                    auth.requestMatchers("/api/users/forgot-password").permitAll();
                    auth.requestMatchers("/api/users/verify/forgot-password").permitAll();
                    auth.requestMatchers("/api/users/verify/forgot-password").permitAll();
                    auth.requestMatchers("/api/users/reset-password").permitAll();
                    auth.requestMatchers("/api/users/*/document/*").permitAll();
                    // Document upload endpoints - public access
                    auth.requestMatchers("/api/documents/upload-base64").permitAll();
                    auth.requestMatchers("/api/documents/upload").permitAll();
                    // Razorpay public endpoints - API key is public, webhook needs special handling
                    auth.requestMatchers("/api/payments/razorpay/key").permitAll();
                    auth.requestMatchers("/api/payments/razorpay/webhook").permitAll();
                    // Static resources - can be public but documents need protection
                    auth.requestMatchers("/uploads/**").permitAll();
                    // Doctor endpoints - allow authenticated users with DOCTOR role
                    // Also allow users to access their own data (doctorId matches their userId)
                    auth.requestMatchers("/api/orders/doctor/**").authenticated();
                    auth.requestMatchers("/api/prescriptions/doctor/**").authenticated();
                    // All other endpoints require authentication
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                    .accessDeniedHandler((request, response, accessDeniedException) -> {
                        System.out.println("=== ACCESS DENIED ===");
                        System.out.println("Request: " + request.getMethod() + " " + request.getRequestURI());
                        System.out.println("Access denied exception: " + accessDeniedException.getMessage());
                        System.out.println("Authentication: " + request.getUserPrincipal());
                        if (request.getUserPrincipal() != null) {
                            System.out.println("User: " + request.getUserPrincipal().getName());
                        }
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        Map<String, String> error = new HashMap<>();
                        error.put("error", "Forbidden");
                        error.put("message", "You don't have permission to access this resource");
                        response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(error));
                    })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
