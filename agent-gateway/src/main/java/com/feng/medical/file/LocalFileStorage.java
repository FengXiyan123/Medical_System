package com.feng.medical.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public final class LocalFileStorage implements FileStorage {
    static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private final Path root;

    public LocalFileStorage(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public StoredFile store(UUID knowledgeBaseId, String extension, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new InvalidUploadException("上传文件不能为空");
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new InvalidUploadException("上传文件不能超过 20MB");
        String key = "knowledge/" + knowledgeBaseId + "/uploads/" + UUID.randomUUID() + "." + extension;
        Path target = safePath(key);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_UPLOAD_BYTES) throw new InvalidUploadException("上传文件不能超过 20MB");
                    digest.update(buffer, 0, read); output.write(buffer, 0, read);
                }
            }
            if (size == 0) throw new InvalidUploadException("上传文件不能为空");
            return new StoredFile(key, HexFormat.of().formatHex(digest.digest()), size);
        } catch (InvalidUploadException exception) {
            delete(key); throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            delete(key); throw new InvalidUploadException("文件保存失败", exception);
        }
    }

    @Override public void delete(String storageKey) {
        try { Files.deleteIfExists(safePath(storageKey)); } catch (IOException ignored) { }
    }
    private Path safePath(String storageKey) {
        Path result = root.resolve(storageKey).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("非法存储键");
        return result;
    }
}
