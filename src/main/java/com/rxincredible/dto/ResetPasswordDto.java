package com.rxincredible.dto;

import lombok.Data;

@Data
public class ResetPasswordDto {
    String email;
    String currentOtp;
    String newPassword;
}
