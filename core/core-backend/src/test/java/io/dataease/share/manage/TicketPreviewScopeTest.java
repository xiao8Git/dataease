package io.dataease.share.manage;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.constant.AuthConstant;
import io.dataease.utils.AuthUtils;
import org.junit.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.Assert.*;

public class TicketPreviewScopeTest {
    private MockHttpServletRequest request;
    private final String sql = "SELECT '__DE_TICKET_TENANT__', '__DE_TICKET_USER__'";
    @Before public void setup() {
        request = new MockHttpServletRequest("POST", "/de2api/chartData/getData");
        request.addHeader(AuthConstant.TOKEN_KEY, "authenticated-by-existing-filter");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuthUtils.setUser(new TokenUserBO(1L, 1L));
    }
    @After public void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        AuthUtils.remove();
    }
    private void preview() {
        request.addHeader("X-DE-PREVIEW-TENANT", "wunding");
        request.addHeader("X-DE-PREVIEW-USER", "admin");
    }
    @Test public void administratorPreviewBinds() {
        preview();
        assertEquals("SELECT 'wunding', 'admin'", TicketScopeSql.bind(sql));
    }
    @Test public void noPreviewStillReturnsNull() {
        assertEquals("SELECT NULL, NULL", TicketScopeSql.bind(sql));
    }
    @Test public void ordinaryUserRejected() {
        preview(); AuthUtils.setUser(new TokenUserBO(2L, 1L));
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.bind(sql));
    }
    @Test public void unauthenticatedRejected() {
        preview(); AuthUtils.remove();
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.bind(sql));
    }
    @Test public void embeddedIdentityRejected() {
        preview(); request.addHeader("X-EMBEDDED-TOKEN", "embedded");
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.bind(sql));
    }
    @Test public void shareTokenNeverFallsBackToPreview() {
        preview(); request.addHeader(AuthConstant.LINK_TOKEN_KEY, "");
        assertEquals("SELECT NULL, NULL", TicketScopeSql.bind(sql));
    }
    @Test public void physicalTableScopesUncheckedTenantField() throws Exception {
        preview(); request.setRequestURI("/de2api/datasetData/previewData");
        var table = physicalTable(true);
        assertTrue(table.getTableName().contains("`tenant_id` = 'wunding'"));
        assertEquals("", table.getTableSchema());
    }
    @Test public void physicalTableWithoutTenantUnchanged() throws Exception {
        var table = physicalTable(false);
        assertEquals("learning", table.getTableName());
        assertEquals("ods", table.getTableSchema());
    }
    @Test public void physicalTableWithoutScopeReturnsNoRows() throws Exception {
        assertTrue(physicalTable(true).getTableName().contains("`tenant_id` = NULL"));
    }
    private io.dataease.extensions.datasource.model.SQLObj physicalTable(boolean tenant) throws Exception {
        var manager = new io.dataease.dataset.manage.DatasetSQLManage();
        var method = java.util.Arrays.stream(manager.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals("getUnionTable")).findFirst().orElseThrow();
        method.setAccessible(true);
        var ds = new io.dataease.extensions.datasource.dto.DatasetTableDTO();
        ds.setType("db"); ds.setDatasourceId(1L);
        var info = new io.dataease.api.dataset.union.DatasetTableInfoDTO();
        info.setTable("learning");
        var schema = new io.dataease.extensions.datasource.dto.DatasourceSchemaDTO();
        schema.setType("mysql");
        var field = new io.dataease.extensions.datasource.dto.DatasetTableFieldDTO();
        field.setOriginName(tenant ? "tenant_id" : "name"); field.setChecked(false);
        return (io.dataease.extensions.datasource.model.SQLObj) method.invoke(manager,
                ds, info, "ods", 0, java.util.List.of(), true, false,
                java.util.Map.of(1L, schema), new java.util.ArrayList<>(), java.util.List.of(field));
    }
    @Test public void datasetPreviewBinds() {
        preview(); request.setRequestURI("/de2api/datasetData/previewData");
        assertEquals("SELECT 'wunding', 'admin'", TicketScopeSql.bind(sql));
    }
    @Test public void datasetDetailPreviewAndTotalBind() {
        preview();
        for (String path : new String[]{"/datasetTree/get/123", "/de2api/datasetTree/get/123",
                "/datasetData/getDatasetTotal", "/de2api/datasetData/getDatasetTotal"}) {
            request.setRequestURI(path);
            assertEquals("SELECT 'wunding', 'admin'", TicketScopeSql.bind(sql));
        }
    }
    @Test public void exportCapturesValidatedScope() {
        preview(); request.setRequestURI("/de2api/datasetTree/exportDataset");
        assertArrayEquals(new String[]{"wunding", "admin"}, TicketScopeSql.captureExportScope());
    }
    @Test public void exportWithoutScopeDoesNotInventIdentity() {
        request.setRequestURI("/de2api/datasetTree/exportDataset");
        assertArrayEquals(new String[]{null, null}, TicketScopeSql.captureExportScope());
    }
    @Test public void asyncExportUsesSnapshotAndClearsThreadScope() throws Exception {
        try (var scope = TicketScopeSql.withExportScope("jochu", "admin")) {
            assertEquals("SELECT 'jochu', 'admin'", TicketScopeSql.bind(sql));
        }
        assertEquals("SELECT NULL, NULL", TicketScopeSql.bind(sql));
    }
    @Test public void exportScopeRestoresAfterFailure() throws Exception {
        try {
            try (var scope = TicketScopeSql.withExportScope("jochu", "admin")) {
                throw new IllegalStateException("test export failure");
            }
        } catch (IllegalStateException expected) { }
        assertEquals("SELECT NULL, NULL", TicketScopeSql.bind(sql));
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.withExportScope("bad'", "admin"));
    }
    @Test public void exportScopeDoesNotLeakToOtherThreads() throws Exception {
        try (var scope = TicketScopeSql.withExportScope("jochu", "admin")) {
            var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var empty = TicketScopeSql.withExportScope(null, null)) {
                    return TicketScopeSql.bind(sql);
                } catch (Exception e) { throw new RuntimeException(e); }
            }).get();
            assertEquals("SELECT NULL, NULL", result);
        }
    }
    @Test public void otherEndpointsRejected() {
        preview(); request.setRequestURI("/de2api/dataset/save");
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.bind(sql));
    }
    @Test public void missingOrUnsafeIdentityRejected() {
        request.addHeader("X-DE-PREVIEW-TENANT", "wunding");
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.bind(sql));
        request.addHeader("X-DE-PREVIEW-USER", "' OR 1=1");
        assertThrows(IllegalArgumentException.class, () -> TicketScopeSql.bind(sql));
    }
}
