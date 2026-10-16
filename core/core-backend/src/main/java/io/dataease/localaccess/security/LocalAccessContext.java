package io.dataease.localaccess.security;

import java.util.Set;

/** Request-only context, populated after signature and live account validation. */
public final class LocalAccessContext {
    public record Identity(long userId, String account, String name, boolean admin,
                           Long tenantId, String tenantCode, String tenantName,
                           String dataUser, Set<String> permissions) {
        public boolean permits(String feature) { return admin || permissions.contains(feature); }
        public void requireTenant() {
            if (tenantId == null) throw new SecurityException("请先选择租户");
        }
    }
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private LocalAccessContext() { }
    public static Identity get() { return CURRENT.get(); }
    public static Identity require() {
        Identity identity = get();
        if (identity == null) throw new SecurityException("请登录本地账号");
        return identity;
    }
    public static void set(Identity identity) { CURRENT.set(identity); }
    public static void clear() { CURRENT.remove(); }
}
