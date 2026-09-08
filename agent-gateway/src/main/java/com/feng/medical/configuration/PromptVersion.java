package com.feng.medical.configuration;

import java.time.Instant;
import java.util.UUID;
public record PromptVersion(UUID id, String name, int version, String template, String hash, boolean active, Instant createdAt) { }
