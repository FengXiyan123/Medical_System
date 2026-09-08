package com.feng.medical.configuration;

import java.time.Instant;
import java.util.UUID;
public record RuntimePolicy(UUID id, String type, int version, String configuration, boolean active, Instant createdAt) { }
