package com.feng.medical.file;

public record StoredFile(String storageKey, String contentSha256, long sizeBytes) {
}
