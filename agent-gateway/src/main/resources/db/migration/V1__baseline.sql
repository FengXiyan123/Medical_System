CREATE TABLE schema_metadata (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schema_name VARCHAR(128) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schema_metadata_name (schema_name)
);

INSERT INTO schema_metadata (schema_name, schema_version)
VALUES ('medical-system', '0.1.0');
