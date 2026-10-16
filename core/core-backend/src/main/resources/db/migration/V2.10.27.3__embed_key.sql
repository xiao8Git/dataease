CREATE TABLE de_embed_key (
    kid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ciphertext VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL
) ENGINE=InnoDB;
