package io.dataease.localaccess.security;

import java.util.Set;

/** Explicit endpoint allowlist. Unknown extensions and new routes are denied by default. */
public final class LocalAccessPolicy {
    private LocalAccessPolicy() { }
    public static final Set<String> PUBLIC = Set.of("/", "/panel", "/login/localLogin", "/dekey", "/model", "/xpackModel",
            "/sysParameter/requestTimeOut", "/sysParameter/defaultSettings", "/sysParameter/ui",
            "/sysParameter/defaultLogin", "/sysParameter/i18nOptions", "/setting/authentication/status",
            "/share/proxyInfo", "/share/validate", "/typeface/listFont", "/typeface/defaultFont", "/perSetting/hmac/info");
    private static final Set<String> COMMON = Set.of("/menu/query", "/user/info", "/user/personInfo", "/user/ipInfo",
            "/user/switchLanguage", "/org/mounted", "/logout", "/login/refresh", "/localAccess/session",
            "/sysParameter/queryOnlineMap", "/sysParameter/queryOnlineMapByMapType", "/sysParameter/sqlbot",
            "/aiComponent/findBaseParams", "/msg/count", "/xpackComponent/viewPlugins", "/symmetricKey",
            "/dataVisualization/interactiveTree", "/localAccess/password", "/sysParameter/shareBase");
    private static final Set<String> DASHBOARD_READ = Set.of("findById", "tree", "findRecent", "findDvType", "query",
            "interactiveTree", "getChart", "getDetail", "chartBaseInfo", "viewOption", "getData", "getFieldData", "getDrillFieldData",
            "queryWithViewId", "queryVisualizationJumpInfo", "queryTargetVisualizationJumpInfo", "getOuterParamsInfo",
            "queryWithVisualizationId", "queryDsWithVisualizationId", "findAll", "querySubjectWithGroup", "find", "viewDetailList",
            "getVisualizationAllLinkageInfo");
    public static boolean allowed(String path, LocalAccessContext.Identity user) {
        if (COMMON.contains(path) || path.startsWith("/localAccess/switch/")) return true;
        if (path.startsWith("/localAccess/")) return user.admin();
        if (path.startsWith("/sysParameter/")) return user.admin();
        if (path.startsWith("/exportCenter/")) return user.admin() || user.permits("dashboard") || user.permits("dataset");
        if (path.equals("/chartData/innerExportDetails") || path.equals("/chartData/innerExportDataSetDetails"))
            return user.tenantId() != null && user.permits("dashboard");
        // Global administration is independent of the selected data-viewing tenant.
        if (user.admin()) return true;
        if (user.tenantId() == null) return false;
        String[] parts = path.split("/");
        if (parts.length < 3) return false;
        String module = parts[1], operation = parts[2];

        if (module.equals("datasetData") && Set.of("enumValue", "enumValueObj", "getFieldTree").contains(operation))
            return user.permits("dashboard") || user.permits("dataset");
        if (Set.of("datasetTree", "datasetData", "datasetField", "datasetTable", "datasetTableSqlLog", "dataAssistant").contains(module))
            return user.permits("dataset");
        if (module.equals("datasource") && Set.of("tree", "getTables", "getTableField", "getSchema").contains(operation))
            return user.permits("dataset");
        if (Set.of("datasource", "driver", "datasourceDriver").contains(module)) return false;
        if (module.equals("store")) return user.permits("dashboard") && Set.of("query", "favorited", "execute").contains(operation);
        if (Set.of("dataVisualization", "chart", "chartData", "linkage", "linkJump", "outerParams",
                "visualizationBackground", "visualizationSubject", "watermark").contains(module))
            return user.permits("dashboard") && (DASHBOARD_READ.contains(operation) || user.admin());
        if (Set.of("geometry", "customGeo", "map", "typeface").contains(module))
            return Set.of("list", "listFont", "defaultFont", "findAll", "get", "geoArea", "worldTree").contains(operation);
        return false;
    }
}
