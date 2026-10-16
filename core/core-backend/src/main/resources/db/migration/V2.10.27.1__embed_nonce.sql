CREATE TABLE IF NOT EXISTS de_embed_nonce (
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (key_id, nonce),
    KEY idx_embed_nonce_expiry (expires_at)
) ENGINE=InnoDB;
