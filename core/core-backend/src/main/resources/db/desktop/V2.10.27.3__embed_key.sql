CREATE TABLE de_embed_key (
    kid VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    ciphertext VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL
);
