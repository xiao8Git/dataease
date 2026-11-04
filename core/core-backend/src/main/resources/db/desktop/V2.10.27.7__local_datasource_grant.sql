-- An empty tenant selection preserves access to all datasources.
CREATE TABLE de_local_datasource_grant (
    tenant_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, datasource_id),
    CONSTRAINT fk_local_datasource_grant_tenant FOREIGN KEY (tenant_id) REFERENCES de_local_tenant(id)
) ENGINE=InnoDB;
