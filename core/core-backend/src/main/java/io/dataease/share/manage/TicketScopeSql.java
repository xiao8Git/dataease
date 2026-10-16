package io.dataease.share.manage;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.constant.AuthConstant;
import io.dataease.utils.CommonBeanFactory;
import io.dataease.utils.DeReflectUtil;
import io.dataease.utils.ServletUtils;
import io.dataease.utils.AuthUtils;
import org.springframework.util.ReflectionUtils;

/** Reserved SQL literals are resolved only from a verified share token, never request filters. */
public final class TicketScopeSql {
    public static final String TENANT = "'__DE_TICKET_TENANT__'";
    public static final String USER = "'__DE_TICKET_USER__'";

    private static final ThreadLocal<String[]> EXPORT_SCOPE = new ThreadLocal<>();

    public static AutoCloseable withExportScope(String tenant, String user) {
        if (tenant != null || user != null) {
            identity(tenant);
            identity(user);
        }
        String[] previous = EXPORT_SCOPE.get();
        EXPORT_SCOPE.set(new String[]{tenant, user});
        return () -> {
            if (previous == null) EXPORT_SCOPE.remove();
            else EXPORT_SCOPE.set(previous);
        };
    }

    public static String[] captureExportScope() {
        String tenant = bind(TENANT);
        String user = bind(USER);
        return new String[]{"NULL".equals(tenant) ? null : tenant.substring(1, tenant.length() - 1),
                "NULL".equals(user) ? null : user.substring(1, user.length() - 1)};
    }

    private TicketScopeSql() { }

    public static Algorithm algorithm(Long resourceId, Long uid) {
        Object manager = CommonBeanFactory.getBean("shareSecretManage");
        Object secret = ReflectionUtils.invokeMethod(DeReflectUtil.findMethod(manager.getClass(), "getSecret"), manager, resourceId, uid);
        if (secret == null) throw new IllegalStateException("Share secret unavailable");
        return Algorithm.HMAC256(secret.toString());
    }

    public static String identity(String value) {
        // Identifiers, not arbitrary SQL: quotes, escapes and whitespace are deliberately excluded.
        if (value == null || !value.matches("[A-Za-z0-9_.:@/-]{1,128}")) {
            throw new IllegalArgumentException("Ticket requires a valid tenant_id and user_id");
        }
        return value;
    }

    public static String bind(String sql) {
        if (sql == null || (!sql.contains(TENANT) && !sql.contains(USER))) return sql;
        var local = io.dataease.localaccess.security.LocalAccessContext.get();
        if (local != null) {
            local.requireTenant();
            return bindValues(sql, local.tenantCode(), local.dataUser());
        }
        String[] exportScope = EXPORT_SCOPE.get();
        if (exportScope != null) {
            return exportScope[0] == null ? sql.replace(TENANT, "NULL").replace(USER, "NULL")
                    : bindValues(sql, exportScope[0], exportScope[1]);
        }
        String token = ServletUtils.getHead(AuthConstant.LINK_TOKEN_KEY);
        if (token == null) {
            String tenant = ServletUtils.getHead("X-DE-PREVIEW-TENANT");
            String user = ServletUtils.getHead("X-DE-PREVIEW-USER");
            if (tenant != null || user != null) {
                var request = ServletUtils.request();
                String path = request.getRequestURI().substring(request.getContextPath().length());
                // Share/embedded identities must never acquire administrator preview scope.
                if (!AuthUtils.isSysAdmin() || ServletUtils.getToken() == null
                        || ServletUtils.getHead("X-EMBEDDED-TOKEN") != null
                        || !("/chartData/getData".equals(path) || "/de2api/chartData/getData".equals(path)
                        || "/datasetData/previewData".equals(path) || "/de2api/datasetData/previewData".equals(path)
                        || "/datasetData/getDatasetTotal".equals(path) || "/de2api/datasetData/getDatasetTotal".equals(path)
                        || path.matches("/(de2api/)?datasetTree/get/[0-9]+")
                        || "/datasetTree/exportDataset".equals(path) || "/de2api/datasetTree/exportDataset".equals(path))) {
                    throw new IllegalArgumentException("Administrator chart preview required");
                }
                return bindValues(sql, tenant, user);
            }
            return sql.replace(TENANT, "NULL").replace(USER, "NULL");
        }
        if (token.isBlank()) return sql.replace(TENANT, "NULL").replace(USER, "NULL");
        DecodedJWT jwt = JWT.decode(token);
        JWT.require(algorithm(jwt.getClaim("resourceId").asLong(), jwt.getClaim("uid").asLong())).build().verify(token);
        return bindValues(sql, jwt.getClaim("scopeTenant").asString(), jwt.getClaim("scopeUser").asString());
    }

    public static String bindValues(String sql, String tenant, String user) {
        return sql.replace(TENANT, "'" + identity(tenant) + "'").replace(USER, "'" + identity(user) + "'");
    }
}
