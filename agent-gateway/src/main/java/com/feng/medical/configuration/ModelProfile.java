package com.feng.medical.configuration;

import java.util.UUID;
public record ModelProfile(UUID id, String name, String provider, String model, String region,
                           String endpoint, String credentialRef, boolean enabled) { }
