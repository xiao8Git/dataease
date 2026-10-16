-- Local export tasks retain their originating tenant across asynchronous execution and retries.
CREATE TABLE de_local_export_scope (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id BIGINT NOT NULL,
    INDEX idx_local_export_owner (user_id, tenant_id)
) ENGINE=InnoDB;
