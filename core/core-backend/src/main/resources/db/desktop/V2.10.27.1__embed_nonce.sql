CREATE TABLE IF NOT EXISTS de_embed_nonce (
    key_id VARCHAR(64) NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (key_id, nonce)
);
CREATE INDEX IF NOT EXISTS idx_embed_nonce_expiry ON de_embed_nonce (expires_at);
