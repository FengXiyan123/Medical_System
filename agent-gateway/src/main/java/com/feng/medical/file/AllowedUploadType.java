package com.feng.medical.file;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

enum AllowedUploadType {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MARKDOWN("md", "text/markdown", "text/x-markdown"),
    TEXT("txt", "text/plain");

    private static final Map<String, AllowedUploadType> BY_EXTENSION = Map.of(
            "pdf", PDF, "docx", DOCX, "md", MARKDOWN, "txt", TEXT);
    private final String extension; private final Set<String> mediaTypes;
    AllowedUploadType(String extension, String... mediaTypes) { this.extension = extension; this.mediaTypes = Set.of(mediaTypes); }
    static AllowedUploadType require(String filename, String contentType) {
        String extension = extension(filename);
        AllowedUploadType type = BY_EXTENSION.get(extension);
        if (type == null || contentType == null || !type.mediaTypes.contains(contentType.toLowerCase(Locale.ROOT))) throw new InvalidUploadException("不支持的文件类型");
        return type;
    }
    String extension() { return extension; }
    private static String extension(String filename) {
        if (filename == null) return ""; int dot = filename.lastIndexOf('.');
        return dot < 1 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
