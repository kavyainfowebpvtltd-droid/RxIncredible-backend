package com.rxincredible.dto;

import lombok.Data;

@Data
public class ResetVerifyPasswordRequestDto {

    private String email;

    private String currentOtp;
}
