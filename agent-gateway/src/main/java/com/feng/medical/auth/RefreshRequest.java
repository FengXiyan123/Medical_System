package com.feng.medical.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
}
