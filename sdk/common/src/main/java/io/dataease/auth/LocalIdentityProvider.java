package io.dataease.auth;

import io.dataease.auth.bo.TokenUserBO;

/** Optional standalone identity implementation. SDK must not depend on its storage. */
public interface LocalIdentityProvider {
    boolean enabled();
    TokenUserBO validate(String token);
    void clear();
}
