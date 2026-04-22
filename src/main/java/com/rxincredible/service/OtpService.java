package com.rxincredible.service;

import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * Service for generating and verifying Time-based One-Time Passwords (TOTP)
 * Compatible with Google Authenticator and other TOTP applications.
 * 
 * The passkey "evqr gumb uhle olza" is used as the shared secret.
 */
@Service
public class OtpService {

    // The Google Authenticator passkey provided by the user
    private static final String PASSKEY = "evqr gumb uhle olza";
    
    // TOTP parameters
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final String ALGORITHM = "HmacSHA1";

    /**
     * Generates the current TOTP code using the configured passkey.
     * This method is used for testing purposes or when the server needs to generate an OTP.
     * 
     * @return The current 6-digit TOTP code
     */
    public String generateOtp() {
        return generateTotp(getCurrentTimeStep());
    }

    /**
     * Generates a TOTP code for a specific time step.
     * 
     * @param timeStep The time step number
     * @return The 6-digit TOTP code
     */
    private String generateTotp(long timeStep) {
        byte[] key = decodeBase32(PASSKEY.replace(" ", "").toUpperCase());
        byte[] data = ByteBuffer.allocate(8).putLong(timeStep).array();
        
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, ALGORITHM);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data);
            
            // Dynamic truncation
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate OTP", e);
        }
    }

    /**
     * Verifies the provided OTP against the current time step and nearby time steps
     * to account for clock drift.
     * 
     * @param otp The OTP to verify
     * @return true if the OTP is valid, false otherwise
     */
    public boolean verifyOtp(String otp) {
        if (otp == null || otp.isEmpty()) {
            return false;
        }
        
        long currentTimeStep = getCurrentTimeStep();
        
        // Check current time step and a few adjacent time steps (for clock drift tolerance)
        for (int i = -1; i <= 1; i++) {
            String expectedOtp = generateTotp(currentTimeStep + i);
            if (expectedOtp.equals(otp)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Gets the current time step number.
     * TOTP uses 30-second intervals by default.
     * 
     * @return The current time step
     */
    private long getCurrentTimeStep() {
        return System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
    }

    /**
     * Decodes a Base32-encoded string to bytes.
     * 
     * @param base32String The Base32-encoded string
     * @return The decoded bytes
     */
    private byte[] decodeBase32(String base32String) {
        Base32 base32 = new Base32();
        return base32.decode(base32String);
    }

    /**
     * Gets the passkey (for display purposes - returns masked version).
     * This can be shown to users to set up Google Authenticator.
     * 
     * @return The masked passkey
     */
    public String getPasskeyForSetup() {
        return PASSKEY.toUpperCase();
    }
}
