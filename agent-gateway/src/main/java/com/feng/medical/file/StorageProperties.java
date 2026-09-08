package com.feng.medical.file;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage")
public record StorageProperties(Path root) {
    public StorageProperties {
        if (root == null) throw new IllegalArgumentException("存储根目录不能为空");
    }
}
