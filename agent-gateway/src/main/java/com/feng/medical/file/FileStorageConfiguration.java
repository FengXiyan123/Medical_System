package com.feng.medical.file;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FileStorageConfiguration {
    @Bean FileStorage fileStorage(StorageProperties properties) { return new LocalFileStorage(properties.root()); }
}
