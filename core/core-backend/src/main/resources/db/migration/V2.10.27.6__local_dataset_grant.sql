-- An empty tenant selection preserves access to all datasets; creators retain their own datasets.
CREATE TABLE de_local_dataset_grant (
    tenant_id BIGINT NOT NULL,
    dataset_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, dataset_id),
    CONSTRAINT fk_local_dataset_grant_tenant FOREIGN KEY (tenant_id) REFERENCES de_local_tenant(id)
) ENGINE=InnoDB;
