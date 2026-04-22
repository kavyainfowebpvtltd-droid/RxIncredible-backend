package com.rxincredible.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RazorpayConfig {
    
    @Value("${razorpay.key}")
    private String razorpayKey;
    
    @Value("${razorpay.secret}")
    private String razorpaySecret;
    
    @Value("${razorpay.webhook.secret:}")
    private String webhookSecret;
    
    @Value("${razorpay.test.mode:true}")
    private boolean testMode;

    @Value("${razorpay.mock.mode:false}")
    private boolean mockMode;
    
    @Bean
    @Lazy
    public RazorpayClient razorpayClient() throws RazorpayException {
        return new RazorpayClient(razorpayKey.trim(), razorpaySecret.trim());
    }
    
    public String getRazorpayKey() {
        return razorpayKey;
    }
    
    public String getRazorpaySecret() {
        return razorpaySecret;
    }
    
    public String getWebhookSecret() {
        return webhookSecret;
    }
    
    public boolean isTestMode() {
        return testMode;
    }

    public String getMode() {
        if (mockMode) {
            return "MOCK";
        }
        return testMode ? "TEST" : "LIVE";
    }

    public boolean isConfigured() {
        return mockMode || (StringUtils.hasText(razorpayKey) && StringUtils.hasText(razorpaySecret));
    }

    public boolean isMockMode() {
        return mockMode;
    }

    public void validateForUsage() {
        if (mockMode) {
            return;
        }
        validateRequiredValue("razorpay.key", razorpayKey);
        validateRequiredValue("razorpay.secret", razorpaySecret);
        validateModeConsistency();
    }

    private void validateRequiredValue(String propertyName, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(propertyName + " is not configured. Set the matching environment variable before using Razorpay payment APIs.");
        }
    }

    private void validateModeConsistency() {
        String trimmedKey = razorpayKey.trim();
        boolean isTestKey = trimmedKey.startsWith("rzp_test_");
        boolean isLiveKey = trimmedKey.startsWith("rzp_live_");

        if (!isTestKey && !isLiveKey) {
            throw new IllegalStateException("razorpay.key must start with rzp_test_ or rzp_live_. Current key format is invalid.");
        }

        if (testMode && !isTestKey) {
            throw new IllegalStateException("Razorpay is configured in TEST mode but the key is not a test key. Set RAZORPAY_TEST_MODE=true with an rzp_test_ key.");
        }

        if (!testMode && !isLiveKey) {
            throw new IllegalStateException("Razorpay is configured in LIVE mode but the key is not a live key. Set RAZORPAY_TEST_MODE=false with an rzp_live_ key.");
        }
    }
}
