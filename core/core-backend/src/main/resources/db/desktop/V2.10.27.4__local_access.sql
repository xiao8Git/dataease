-- Independent local identity and tenant grants; no enterprise permission tables.
CREATE TABLE de_local_tenant (
 id BIGINT NOT NULL PRIMARY KEY,
 code VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 name VARCHAR(128) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;
CREATE TABLE de_local_department (
 id BIGINT NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 name VARCHAR(128) NOT NULL,
 permissions VARCHAR(1024) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE KEY uk_local_department (tenant_id, name),
 UNIQUE KEY uk_local_department_tenant (id, tenant_id),
 CONSTRAINT fk_local_department_tenant FOREIGN KEY (tenant_id) REFERENCES de_local_tenant(id)
) ENGINE=InnoDB;
CREATE TABLE de_local_user (
 id BIGINT NOT NULL PRIMARY KEY,
 account VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 name VARCHAR(128) NOT NULL,
 password_hash VARCHAR(256) NOT NULL,
 token_secret VARCHAR(128) NOT NULL,
 language VARCHAR(10) NOT NULL DEFAULT 'zh-CN',
 tenant_id BIGINT NULL,
 department_id BIGINT NULL,
 data_user VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 CONSTRAINT fk_local_user_department FOREIGN KEY (department_id, tenant_id) REFERENCES de_local_department(id, tenant_id),
 CONSTRAINT ck_local_user_binding CHECK ((platform_admin = TRUE AND tenant_id IS NULL AND department_id IS NULL) OR (platform_admin = FALSE AND tenant_id IS NOT NULL AND department_id IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE de_local_dashboard_grant (
 tenant_id BIGINT NOT NULL,
 resource_id BIGINT NOT NULL,
 PRIMARY KEY (tenant_id, resource_id),
 CONSTRAINT fk_local_grant_tenant FOREIGN KEY (tenant_id) REFERENCES de_local_tenant(id)
) ENGINE=InnoDB;
