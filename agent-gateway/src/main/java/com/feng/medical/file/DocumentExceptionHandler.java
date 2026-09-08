package com.feng.medical.file;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/** Returns actionable validation messages for administrator document operations. */
@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler {

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<Map<String, String>> invalidUpload(InvalidUploadException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", exception.getMessage());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> invalidMultipart(MultipartException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART", "上传文件无法读取，请重新选择文件后重试");
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
