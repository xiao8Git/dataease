package io.dataease.localaccess;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.dataease.api.localaccess.LocalAccessApi.*;
import io.dataease.localaccess.dao.LocalAccessMapper;
import io.dataease.localaccess.manage.LocalAccessManage;
import io.dataease.localaccess.security.LocalAccessContext;
import io.dataease.localaccess.security.LocalAccessPolicy;
import io.dataease.localaccess.security.LocalPassword;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.Assert.*;

public class LocalAccessManageTest {
    private SqlSession session;
    private LocalAccessMapper mapper;
    private LocalAccessManage manage;
    private JdbcTemplate jdbc;
    private static final String PASSWORD = "local-test-password-123";
    @Before public void setup() throws Exception {
        new io.dataease.utils.IDUtils().setSnowFlake(new io.dataease.utils.SnowFlake(1, 1));
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V2.10.27.4__local_access.sql"))
                .replaceAll("(?m)^--.*$", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        String exportSql = Files.readString(Path.of("src/main/resources/db/migration/V2.10.27.5__local_export_scope.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB", "");
        jdbc.execute(exportSql);
        String datasetGrantSql = Files.readString(Path.of("src/main/resources/db/migration/V2.10.27.6__local_dataset_grant.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB", "");
        jdbc.execute(datasetGrantSql);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V2.10.27.7__local_datasource_grant.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB", ""));
        jdbc.execute("CREATE TABLE core_datasource(id BIGINT PRIMARY KEY,name VARCHAR(50),type VARCHAR(20))");
        jdbc.execute("INSERT INTO core_datasource VALUES(701,'selected','Excel'),(702,'hidden','mysql'),(703,'folder','folder')");
        jdbc.execute("CREATE TABLE core_dataset_group(id BIGINT PRIMARY KEY,pid BIGINT,name VARCHAR(50),node_type VARCHAR(20),create_by VARCHAR(64))");
        jdbc.execute("INSERT INTO core_dataset_group VALUES(301,0,'selected','dataset','1'),(302,400,'hidden','dataset','999'),(400,0,'folder','folder','999')");
        jdbc.execute("CREATE TABLE core_dataset_table(id BIGINT PRIMARY KEY,dataset_group_id BIGINT,datasource_id BIGINT)");
        jdbc.execute("INSERT INTO core_dataset_table VALUES(801,301,701),(802,302,702)");
        jdbc.execute("CREATE TABLE data_visualization_info(id BIGINT, name VARCHAR(50),type VARCHAR(20),node_type VARCHAR(20),delete_flag BOOLEAN,pid BIGINT)");
        jdbc.execute("INSERT INTO data_visualization_info VALUES(101,'shared','dashboard','leaf',FALSE,0),(102,'other','dashboard','leaf',FALSE,0)");
        jdbc.execute("CREATE TABLE core_chart_view(id BIGINT, scene_id BIGINT,table_id BIGINT)");
        jdbc.execute("CREATE TABLE snapshot_core_chart_view(id BIGINT, scene_id BIGINT,table_id BIGINT)");
        jdbc.execute("INSERT INTO core_chart_view VALUES(201,101,301),(202,102,302)");
        jdbc.execute("CREATE TABLE core_dataset_table_field(id BIGINT PRIMARY KEY,dataset_group_id BIGINT,chart_id BIGINT)");
        jdbc.execute("INSERT INTO core_dataset_table_field VALUES(501,301,NULL),(502,301,201),(503,302,202)");
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT); config.addMapper(LocalAccessMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true); mapper = session.getMapper(LocalAccessMapper.class);
        manage = new LocalAccessManage(mapper, true, PASSWORD); manage.run(null);
        admin();
        manage.saveTenant(new Tenant(null,"a","Tenant A",true)); manage.saveTenant(new Tenant(null,"b","Tenant B",true));
        for (Tenant t : mapper.tenants()) manage.saveDepartment(new Department(null,t.id(),"Operations",Set.of("dashboard"),true));
        Tenant a = mapper.tenants().get(0);
        manage.saveUser(new UserEditor(null,"operator","Operator",a.id(),mapper.departments(a.id()).get(0).getId(),"employee",true,PASSWORD));
        manage.saveGrants(new Grant(a.id(),List.of(101L)));
    }
    private void admin() { manage.validate(manage.login("admin",PASSWORD).getToken()); }
    private String operatorToken() { return manage.login("operator",PASSWORD).getToken(); }
    @Test public void datasourceSelectionsValidateDeduplicateAndClear() {
        Long tenant = mapper.tenants().get(0).id();
        assertEquals(2, manage.datasources().size());
        assertTrue(manage.datasourceGrants(tenant).datasourceIds().isEmpty());
        manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of(701L,701L)));
        assertEquals(List.of(701L),manage.datasourceGrants(tenant).datasourceIds());
        assertThrows(IllegalArgumentException.class, () -> manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of(702L,999L))));
        assertThrows(IllegalArgumentException.class, () -> manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of(703L))));
        assertThrows(IllegalArgumentException.class, () -> manage.saveDatasourceGrants(new DatasourceGrant(999L,List.of())));
        assertThrows(IllegalArgumentException.class, () -> manage.saveDatasourceGrants(new DatasourceGrant(tenant,null)));
        assertThrows(IllegalArgumentException.class, () -> manage.datasourceGrants(999L));
        assertEquals(List.of(701L),manage.datasourceGrants(tenant).datasourceIds());
        manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of()));
        assertTrue(manage.datasourceGrants(tenant).datasourceIds().isEmpty());
    }
    @Test public void datasourceSelectionRestrictsOrdinaryUsersAndNestedDatasetSources() {
        Long tenant = mapper.tenants().get(0).id();
        manage.validate(operatorToken());
        manage.requireDatasource(701L); manage.requireDatasource(702L);
        assertThrows(SecurityException.class, () -> manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of(701L))));
        assertThrows(SecurityException.class, () -> manage.datasources());
        assertThrows(SecurityException.class, () -> manage.datasourceGrants(tenant));
        assertThrows(SecurityException.class, () -> manage.requireDatasource(-1L));
        assertThrows(SecurityException.class, () -> manage.requireDatasource(703L));
        assertThrows(SecurityException.class, () -> manage.requireDatasource(999L));
        admin();manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of(701L)));
        manage.requireDatasource(702L);
        manage.validate(operatorToken());manage.requireDatasource(701L);
        assertThrows(SecurityException.class, () -> manage.requireDatasource(702L));
        manage.requireDataset(301L);
        assertThrows(SecurityException.class, () -> manage.requireDataset(302L));
        var parent = new io.dataease.api.dataset.union.UnionDTO();
        var child = new io.dataease.api.dataset.union.UnionDTO();
        var table = new io.dataease.extensions.datasource.dto.DatasetTableDTO();table.setDatasourceId(702L);
        child.setCurrentDs(table);parent.setChildrenDs(List.of(child));
        var dataset = new io.dataease.api.dataset.union.DatasetGroupInfoDTO();dataset.setUnion(List.of(parent));
        assertThrows(SecurityException.class, () -> manage.requireDatasetSources(dataset));
        table.setDatasourceId(701L);manage.requireDatasetSources(dataset);
        admin();manage.saveDatasourceGrants(new DatasourceGrant(tenant,List.of()));
        manage.validate(operatorToken());manage.requireDatasource(702L);
    }
    @After public void cleanup() { LocalAccessContext.clear(); if (session != null) session.close(); }
    @Test public void userFilterWithoutTenantReturnsOnlyPlatformAdministrators() {
        assertEquals(List.of("admin"), manage.users(null).stream().map(User::account).toList());
        Tenant a = mapper.tenants().get(0), b = mapper.tenants().get(1);
        assertEquals(List.of("operator"), manage.users(a.id()).stream().map(User::account).toList());
        assertTrue(manage.users(b.id()).isEmpty());
        manage.validate(operatorToken());
        assertThrows(SecurityException.class, () -> manage.users(null));
    }
    @Test public void adminCanReturnToGlobalScopeWithoutChangingAccountBinding() {
        manage.validate(manage.switchTenant(mapper.tenants().get(0).id()).getToken());
        String scoped = manage.refresh().getToken();
        String global = manage.switchTenant(0L).getToken();
        assertThrows(Exception.class, () -> manage.validate(scoped));
        manage.validate(global);
        assertNull(mapper.user(1L).getTenantId());
        assertNull(manage.session().tenantId());
        assertTrue(LocalAccessPolicy.allowed("/datasource/tree", LocalAccessContext.require()));
        assertTrue(LocalAccessPolicy.allowed("/sysParameter/save", LocalAccessContext.require()));
        assertThrows(SecurityException.class, () -> manage.requireDashboard(101L));
        manage.validate(operatorToken());
        assertThrows(SecurityException.class, () -> manage.switchTenant(0L));
    }
    @Test public void ordinaryAccountsNeedTenantAndMatchingRoleAndCannotGainGlobalFeatures() {
        var a = mapper.tenants().get(0);
        var role = mapper.departments(a.id()).get(0);
        assertThrows(IllegalArgumentException.class, () -> manage.saveUser(new UserEditor(null,"invalid","Invalid",null,role.getId(),"user",true,PASSWORD)));
        assertThrows(IllegalArgumentException.class, () -> manage.saveDepartment(new Department(null,a.id(),"Global",Set.of("datasource"),true)));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,datasource' WHERE id=?",role.getId());
        manage.validate(operatorToken());
        assertFalse(LocalAccessContext.require().permits("datasource"));
        assertFalse(LocalAccessPolicy.allowed("/datasource/save",LocalAccessContext.require()));
        assertFalse(LocalAccessPolicy.allowed("/sysParameter/save",LocalAccessContext.require()));
    }
    @Test public void exportTaskRestoresOriginalTenantAndRejectsOtherTenantsAndOwners() {
        var exports = new io.dataease.localaccess.manage.LocalExportScope(mapper, manage);
        var a = mapper.tenants().get(0); var b = mapper.tenants().get(1);
        manage.validate(manage.switchTenant(a.id()).getToken());
        exports.create("7001", "chart", 201L);
        manage.validate(manage.switchTenant(b.id()).getToken());
        assertThrows(SecurityException.class, () -> exports.requireCurrent("7001"));
        var previous = LocalAccessContext.require();
        try (var context = exports.open("7001")) {
            assertEquals(a.id(),LocalAccessContext.require().tenantId());
            assertEquals(a.id(),io.dataease.utils.AuthUtils.getUser().getDefaultOid());
            assertEquals("'a'",io.dataease.share.manage.TicketScopeSql.bind("'__DE_TICKET_TENANT__'"));
        }
        assertSame(previous, LocalAccessContext.require());
        assertNull(io.dataease.utils.AuthUtils.getUser());
        manage.validate(operatorToken());
        assertThrows(SecurityException.class, () -> exports.requireCurrent("7001"));
        jdbc.update("UPDATE de_local_tenant SET enabled=FALSE WHERE id=?", a.id());
        assertThrows(SecurityException.class, () -> exports.open("7001"));
    }
    @Test public void exportsRecheckGrantsRolesAndDownloadSecrets() {
        var exports = new io.dataease.localaccess.manage.LocalExportScope(mapper, manage);
        manage.validate(operatorToken());
        exports.create("7002", "chart", 201L);
        assertNotNull(exports.downloadSecret("7002"));
        assertThrows(SecurityException.class, () -> exports.create("7003", "dataset", 301L));
        assertThrows(SecurityException.class, () -> exports.create("7004", "chart", 202L));
        jdbc.update("DELETE FROM de_local_dashboard_grant");
        assertThrows(SecurityException.class, () -> exports.open("7002"));
        assertThrows(SecurityException.class, () -> exports.downloadSecret("7002"));
    }
    @Test public void ordinaryUserIsBoundToTenantAndDepartment() {
        manage.validate(operatorToken()); var identity = LocalAccessContext.require();
        assertEquals("a",identity.tenantCode()); assertTrue(identity.permits("dashboard")); assertFalse(identity.permits("dataset"));
        assertEquals(1,manage.session().tenants().size());
        assertThrows(SecurityException.class, () -> manage.switchTenant(mapper.tenants().get(1).id()));
        assertThrows(SecurityException.class, manage::tenants);
    }
    @Test public void adminStartsWithoutTenantAndSwitchInvalidatesOldToken() {
        String old = manage.login("admin",PASSWORD).getToken(); manage.validate(old);
        assertNull(manage.session().tenantId()); assertThrows(SecurityException.class, () -> manage.requireDashboard(101L));
        String token = manage.switchTenant(mapper.tenants().get(0).id()).getToken();
        assertThrows(Exception.class, () -> manage.validate(old));
        manage.validate(token); manage.requireDashboard(101L);
        assertThrows(SecurityException.class, () -> manage.requireDashboard(102L));
    }
    @Test public void grantsAndChartDatasetCannotBeForged() {
        manage.validate(operatorToken()); manage.requireChart(201L,301L);
        assertThrows(SecurityException.class, () -> manage.requireChart(201L,302L));
        assertThrows(SecurityException.class, () -> manage.requireChart(202L,302L));
        assertThrows(SecurityException.class, () -> manage.requireDashboard(102L));
    }
    @Test public void disabledUserTenantAndDepartmentAreRejectedOnNextRequest() {
        String token=operatorToken(); var user=mapper.account("operator");
        jdbc.update("UPDATE de_local_user SET enabled=FALSE WHERE id=?",user.getId());
        assertThrows(SecurityException.class, () -> manage.validate(token));
        jdbc.update("UPDATE de_local_user SET enabled=TRUE WHERE id=?",user.getId());
        jdbc.update("UPDATE de_local_department SET enabled=FALSE WHERE id=?",user.getDepartmentId());
        assertThrows(SecurityException.class, () -> manage.validate(token));
        jdbc.update("UPDATE de_local_department SET enabled=TRUE WHERE id=?",user.getDepartmentId());
        jdbc.update("UPDATE de_local_tenant SET enabled=FALSE WHERE id=?",user.getTenantId());
        assertThrows(SecurityException.class, () -> manage.validate(token));
    }
    @Test public void permissionsRefreshWithoutNewLoginAndUnknownRoutesDeny() {
        String token=operatorToken(); var user=mapper.account("operator");
        jdbc.update("UPDATE de_local_department SET permissions='dataset' WHERE id=?",user.getDepartmentId());
        manage.validate(token); var current=LocalAccessContext.require();
        assertTrue(LocalAccessPolicy.allowed("/datasetTree/tree",current));
        assertFalse(LocalAccessPolicy.allowed("/chartData/getData",current));
        assertFalse(LocalAccessPolicy.allowed("/newExtension/readAll",current));
        assertFalse(LocalAccessPolicy.allowed("/localAccess/tenants",current));
    }
    @Test public void rejectsInvalidSignatureAndUserRebinding() {
        String token=operatorToken(); String[] parts=token.split("\\.");
        assertThrows(Exception.class, () -> manage.validate(parts[0]+"."+parts[1]+".AAAA"));
        admin(); var user=mapper.account("operator"); var b=mapper.tenants().get(1);
        assertThrows(IllegalArgumentException.class, () -> manage.saveUser(new UserEditor(user.getId(),user.getAccount(),user.getName(),b.id(),mapper.departments(b.id()).get(0).getId(),"employee",true,"")));
    }
    @Test public void passwordResetAndLogoutRevokeTokens() {
        String old=operatorToken(); admin(); var u=mapper.account("operator");
        manage.saveUser(new UserEditor(u.getId(),u.getAccount(),u.getName(),u.getTenantId(),u.getDepartmentId(),u.getDataUser(),true,"replacement-password-123"));
        assertThrows(Exception.class, () -> manage.validate(old));
        String fresh=manage.login("operator","replacement-password-123").getToken(); manage.validate(fresh); manage.logout();
        assertThrows(Exception.class, () -> manage.validate(fresh));
        assertFalse(mapper.account("operator").getPasswordHash().contains("replacement"));
    }
    @Test public void rejectsWrongCredentialsAndShortPasswords() {
        assertThrows(SecurityException.class, () -> manage.login("operator","wrong"));
        assertThrows(IllegalArgumentException.class, () -> LocalPassword.hash("short"));
        assertThrows(SecurityException.class, () -> manage.login("missing",PASSWORD));
    }
    @Test public void expiredAndNonExpiringTokensAreRejected() {
        var user = mapper.account("operator");
        var algorithm = Algorithm.HMAC256(user.getTokenSecret());
        var builder = JWT.create().withIssuer("dataease-local-access").withSubject(user.getId().toString())
                .withClaim("uid", user.getId()).withClaim("oid", user.getTenantId());
        assertThrows(Exception.class, () -> manage.validate(builder.sign(algorithm)));
        String expired = builder.withExpiresAt(new Date(System.currentTimeMillis() - 60000)).sign(algorithm);
        assertThrows(Exception.class, () -> manage.validate(expired));
    }
    @Test public void jsonPreservesSnowflakeIdsAndNeverReturnsPasswords() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        long id = 9007199254740993L;
        assertEquals("9007199254740993", json.readTree(json.writeValueAsString(new Tenant(id,"a","A",true))).get("id").textValue());
        assertEquals("9007199254740993", json.readTree(json.writeValueAsString(new Grant(id,List.of(id)))).get("resourceIds").get(0).textValue());
        assertFalse(json.writeValueAsString(new UserEditor(id,"test","Test",id,id,"user",true,"secret-password")).contains("secret-password"));
        assertEquals("9007199254740993", json.readTree(json.writeValueAsString(new DatasetGrant(id,List.of(id)))).get("datasetIds").get(0).textValue());
    }
    @Test public void interceptorDeniesDepartmentEndpointsAndAllowsChartQueries() throws Exception {
        manage.validate(operatorToken());
        var interceptor = new io.dataease.localaccess.security.LocalAccessInterceptor(manage, new com.fasterxml.jackson.databind.ObjectMapper());
        var server = new io.dataease.localaccess.server.LocalAccessServer(manage);
        var handler = new org.springframework.web.method.HandlerMethod(server, server.getClass().getMethod("session"));
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/datasetTree/tree");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler)); assertEquals(403,response.getStatus());
        request.setRequestURI("/chartData/getData");
        assertTrue(interceptor.preHandle(request,new org.springframework.mock.web.MockHttpServletResponse(),handler));
        request.setRequestURI("/localAccess/tenants");
        assertFalse(interceptor.preHandle(request,new org.springframework.mock.web.MockHttpServletResponse(),handler));
    }
    @Test public void anonymousCanLoadEntryPageButNotSession() throws Exception {
        LocalAccessContext.clear();
        var interceptor = new io.dataease.localaccess.security.LocalAccessInterceptor(manage, new com.fasterxml.jackson.databind.ObjectMapper());
        var controller = new io.dataease.home.IndexController();
        var handler = new org.springframework.web.method.HandlerMethod(controller, controller.getClass().getMethod("index"));
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/");
        assertTrue(interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), handler));
        request.setRequestURI("/localAccess/session");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(403, response.getStatus());
        request.setRequestURI("/localAccess/branding");
        assertTrue(interceptor.preHandle(request,new org.springframework.mock.web.MockHttpServletResponse(),handler));
        request.setRequestURI("/localAccess/branding/image");
        assertTrue(interceptor.preHandle(request,new org.springframework.mock.web.MockHttpServletResponse(),handler));
        request.setMethod("POST");request.setRequestURI("/localAccess/branding/logo");
        response=new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request,response,handler));assertEquals(403,response.getStatus());
    }
    @Test public void localScopeIgnoresClientPreviewHeaders() {
        manage.validate(operatorToken());
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/chartData/getData");
        request.addHeader("X-DE-PREVIEW-TENANT", "b"); request.addHeader("X-DE-PREVIEW-USER", "admin");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertEquals("'a' 'employee'", io.dataease.share.manage.TicketScopeSql.bind("'__DE_TICKET_TENANT__' '__DE_TICKET_USER__'"));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
    @Test public void datasetSelectionDefaultsToAllAndRestrictsOnlyAfterSelection() {
        var tenant = mapper.tenants().get(0);
        assertTrue(manage.datasetGrants(tenant.id()).datasetIds().isEmpty());
        manage.validate(operatorToken());
        manage.requireDataset(301L); manage.requireDataset(302L);
        admin(); manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(301L,301L)));
        assertEquals(List.of(301L),manage.datasetGrants(tenant.id()).datasetIds());
        manage.validate(operatorToken());
        manage.requireDataset(301L);
        assertThrows(SecurityException.class,()->manage.requireDataset(302L));
        admin(); manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of()));
        manage.validate(operatorToken()); manage.requireDataset(302L);
    }
    @Test public void creatorCanReadNewDatasetWithoutChangingTenantSelection() {
        var tenant = mapper.tenants().get(0);
        manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(301L)));
        manage.validate(operatorToken());
        jdbc.update("INSERT INTO core_dataset_group VALUES(303,0,'own new dataset','dataset',?)",Long.toString(LocalAccessContext.require().userId()));
        manage.requireDataset(303L);
        assertThrows(SecurityException.class,()->manage.requireDataset(302L));
        assertThrows(SecurityException.class,()->manage.requireDatasetDeletion(400L));
    }
    @Test public void invalidDatasetSelectionCannotReplaceExistingConfiguration() {
        var a = mapper.tenants().get(0); var b = mapper.tenants().get(1);
        manage.saveDatasetGrants(new DatasetGrant(a.id(),List.of(301L)));
        manage.saveDatasetGrants(new DatasetGrant(b.id(),List.of(302L)));
        assertThrows(IllegalArgumentException.class,()->manage.saveDatasetGrants(new DatasetGrant(a.id(),List.of(999L))));
        assertThrows(IllegalArgumentException.class,()->manage.saveDatasetGrants(new DatasetGrant(a.id(),List.of(400L))));
        assertEquals(List.of(301L),manage.datasetGrants(a.id()).datasetIds());
        assertEquals(List.of(302L),manage.datasetGrants(b.id()).datasetIds());
        manage.validate(operatorToken());
        assertThrows(SecurityException.class,()->manage.saveDatasetGrants(new DatasetGrant(a.id(),List.of())));
        assertThrows(SecurityException.class,()->manage.datasets());
    }
    @Test public void dtoGuardRejectsHiddenWritesAndKeepsCreatorImmutable() throws Throwable {
        var tenant = mapper.tenants().get(0);
        manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(301L)));
        manage.validate(operatorToken());
        var guard = new io.dataease.localaccess.security.LocalDatasetGuard(manage);
        var point = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        var signature = org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        org.mockito.Mockito.when(signature.getName()).thenReturn("save");
        org.mockito.Mockito.when(point.getSignature()).thenReturn(signature);
        var dto = new io.dataease.api.dataset.union.DatasetGroupInfoDTO();
        dto.setId(302L); dto.setPid(0L);
        org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{dto});
        assertThrows(SecurityException.class,()->guard.check(point));
        org.mockito.Mockito.verify(point,org.mockito.Mockito.never()).proceed();
        dto.setId(301L); dto.setCreateBy(Long.toString(LocalAccessContext.require().userId()));
        guard.check(point);
        assertEquals("1",dto.getCreateBy());
    }
    @Test public void datasetVisibilityAlsoAppliesToChartQueries() {
        var tenant = mapper.tenants().get(0);
        manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(302L)));
        manage.validate(operatorToken());
        assertThrows(SecurityException.class,()->manage.requireChart(201L,301L));
        admin(); manage.requireDataset(301L);
    }
    @Test public void operationPermissionsAreIndependentAndRefreshOnExistingToken() {
        String token = operatorToken();
        manage.validate(token);
        var user = mapper.account("operator");
        assertFalse(LocalAccessPolicy.allowed("/dataVisualization/updateCanvas",LocalAccessContext.require()));
        assertFalse(LocalAccessPolicy.allowed("/dataVisualization/deleteLogic/101/dashboard",LocalAccessContext.require()));
        assertThrows(SecurityException.class,()->manage.requireDatasetDeletion(301L));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit,dataset' WHERE id=?",user.getDepartmentId());
        manage.validate(token);
        manage.requireDashboardOperation(101L,"dashboard_edit");
        assertTrue(LocalAccessPolicy.allowed("/dataVisualization/updateCanvas",LocalAccessContext.require()));
        assertTrue(LocalAccessPolicy.allowed("/chart/listByDQ/301/201",LocalAccessContext.require()));
        assertFalse(LocalAccessPolicy.allowed("/dataVisualization/deleteLogic/101/dashboard",LocalAccessContext.require()));
        assertFalse(LocalAccessPolicy.allowed("/datasetTree/delete/301",LocalAccessContext.require()));
        assertTrue(LocalAccessPolicy.allowed("/dataVisualization/saveCanvas",LocalAccessContext.require()));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_delete,dataset,dataset_delete' WHERE id=?",user.getDepartmentId());
        manage.validate(token);
        manage.requireDashboardOperation(101L,"dashboard_delete");manage.requireDatasetDeletion(301L);
        assertFalse(LocalAccessPolicy.allowed("/dataVisualization/updateCanvas",LocalAccessContext.require()));
        assertTrue(LocalAccessPolicy.allowed("/dataVisualization/deleteLogic/101/dashboard",LocalAccessContext.require()));
        assertTrue(LocalAccessPolicy.allowed("/datasetTree/delete/301",LocalAccessContext.require()));
        assertThrows(SecurityException.class,()->manage.requireDashboardOperation(102L,"dashboard_delete"));
        admin();manage.requireDatasetDeletion(301L);
    }
    @Test public void operationPermissionsRequireTheirBaseFeature() {
        var tenant = mapper.tenants().get(0);
        assertThrows(IllegalArgumentException.class,()->manage.saveDepartment(new Department(null,tenant.id(),"bad",Set.of("dashboard_edit"),true)));
        assertThrows(IllegalArgumentException.class,()->manage.saveDepartment(new Department(null,tenant.id(),"bad",Set.of("dashboard_delete"),true)));
        assertThrows(IllegalArgumentException.class,()->manage.saveDepartment(new Department(null,tenant.id(),"bad",Set.of("dataset_delete"),true)));
    }
    @Test public void editorCannotReuseAnotherDashboardChartOrHiddenDataset() {
        var tenant=mapper.tenants().get(0);var user=mapper.account("operator");
        manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(301L)));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",user.getDepartmentId());
        manage.validate(operatorToken());
        var view=new io.dataease.extensions.view.dto.ChartViewDTO();view.setId(201L);view.setSceneId(101L);view.setTableId(301L);
        manage.requireEditableChart(view);
        view.setId(202L);
        assertThrows(SecurityException.class,()->manage.requireEditableChart(view));
        view.setId(900L);view.setTableId(302L);
        assertThrows(SecurityException.class,()->manage.requireEditableChart(view));
        view.setTableId(301L);manage.requireEditableChart(view);
        jdbc.update("INSERT INTO snapshot_core_chart_view VALUES(900,102,301)");
        assertThrows(SecurityException.class,()->manage.requireEditableChart(view));
    }
    @Test public void dashboardGuardRejectsReadOnlySnapshotInitializationAndOtherResourceWrites() throws Throwable {
        manage.validate(operatorToken());
        var guard=new io.dataease.localaccess.security.LocalDashboardGuard(manage);
        var point=org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        var signature=org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        org.mockito.Mockito.when(point.getSignature()).thenReturn(signature);
        org.mockito.Mockito.when(signature.getName()).thenReturn("findById");
        var request=new io.dataease.api.visualization.request.DataVisualizationBaseRequest();request.setId(101L);request.setSource("main-edit");
        org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{request});
        assertThrows(SecurityException.class,()->guard.check(point));
        org.mockito.Mockito.verify(point,org.mockito.Mockito.never()).proceed();
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());guard.check(point);
        request.setId(102L);
        assertThrows(SecurityException.class,()->guard.check(point));
    }
    @Test public void deletingFolderStillRequiresVisibilityForEveryDescendant() {
        var tenant=mapper.tenants().get(0);
        manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(301L)));
        jdbc.update("UPDATE de_local_department SET permissions='dataset,dataset_delete' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        manage.requireDatasetDeletion(301L);
        assertThrows(SecurityException.class,()->manage.requireDatasetDeletion(400L));
    }
    @Test public void editorReceivesManageWeightsAndNewBoardsAreGrantedToCurrentTenant() {
        var core=new io.dataease.visualization.manage.CoreVisualizationManage();
        manage.validate(operatorToken());
        assertEquals(Integer.valueOf(1),org.springframework.test.util.ReflectionTestUtils.invokeMethod(core,"localWeight",9));
        assertThrows(SecurityException.class,()->manage.createdDashboard(103L,"leaf"));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        assertEquals(Integer.valueOf(9),org.springframework.test.util.ReflectionTestUtils.invokeMethod(core,"localWeight",9));
        assertEquals(Integer.valueOf(7),org.springframework.test.util.ReflectionTestUtils.invokeMethod(core,"localWeight",7));
        manage.createdDashboard(103L,"leaf");manage.requireDashboard(103L);
        assertEquals(0,mapper.hasGrant(mapper.tenants().get(1).id(),103L));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_delete' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        assertEquals(Integer.valueOf(7),org.springframework.test.util.ReflectionTestUtils.invokeMethod(core,"localWeight",9));
        assertEquals(Integer.valueOf(1),org.springframework.test.util.ReflectionTestUtils.invokeMethod(core,"localWeight",7));
    }
    @Test public void creationAndUnsavedChartPreviewUseEditorPermissionAndDatasetVisibility() {
        var tenant=mapper.tenants().get(0);
        manage.saveDatasetGrants(new DatasetGrant(tenant.id(),List.of(301L)));
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        var request=new io.dataease.api.visualization.request.DataVisualizationBaseRequest();request.setType("dashboard");request.setPid(0L);request.setNodeType("leaf");
        var view=new io.dataease.extensions.view.dto.ChartViewDTO();view.setId(900L);view.setTableId(301L);
        request.setCanvasViewInfo(Map.of(900L,view));manage.requireDashboardCreation(request);manage.requireChartPreview(view);
        view.setTableId(302L);assertThrows(SecurityException.class,()->manage.requireDashboardCreation(request));
        view.setTableId(301L);view.setId(201L);manage.requireChartPreview(view);
        assertThrows(SecurityException.class,()->manage.requireDashboardCreation(request));
        view.setId(900L);request.setId(101L);assertThrows(SecurityException.class,()->manage.requireDashboardCreation(request));
        request.setId(null);request.setOptType("copy");assertThrows(SecurityException.class,()->manage.requireDashboardCreation(request));
        request.setOptType(null);request.setNodeType("folder");assertThrows(SecurityException.class,()->manage.requireDashboardCreation(request));
        request.setNodeType("leaf");request.setPid(102L);assertThrows(SecurityException.class,()->manage.requireDashboardCreation(request));
        assertTrue(LocalAccessPolicy.allowed("/dataVisualization/nameCheck",LocalAccessContext.require()));
        assertTrue(LocalAccessPolicy.allowed("/datasetField/listByDatasetGroup/301",LocalAccessContext.require()));
        assertFalse(LocalAccessPolicy.allowed("/datasetTree/save",LocalAccessContext.require()));
        assertFalse(LocalAccessPolicy.allowed("/dataVisualization/deleteLogic/101/dashboard",LocalAccessContext.require()));
    }
    @Test public void nameCheckAndCreateGuardCoverTheActualEditorSaveSequence() throws Throwable {
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        var guard=new io.dataease.localaccess.security.LocalDashboardGuard(manage);
        var point=org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        var signature=org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        org.mockito.Mockito.when(point.getSignature()).thenReturn(signature);
        var request=new io.dataease.api.visualization.request.DataVisualizationBaseRequest();request.setType("dashboard");request.setPid(0L);
        org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{request});
        org.mockito.Mockito.when(signature.getName()).thenReturn("nameCheck");guard.check(point);
        org.mockito.Mockito.when(signature.getName()).thenReturn("saveCanvas");guard.check(point);
        request.setId(102L);assertThrows(SecurityException.class,()->guard.check(point));
        org.mockito.Mockito.when(signature.getName()).thenReturn("nameCheck");assertThrows(SecurityException.class,()->guard.check(point));
        request.setId(101L);guard.check(point);
    }
    @Test public void dashboardOnlyEditorCanAddOwnChartFieldButCannotOverwriteDatasetFields() throws Throwable {
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        var guard=new io.dataease.localaccess.security.LocalDatasetGuard(manage);
        var point=org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        var signature=org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        org.mockito.Mockito.when(point.getSignature()).thenReturn(signature);
        org.mockito.Mockito.when(signature.getName()).thenReturn("save");
        var field=new io.dataease.extensions.datasource.dto.DatasetTableFieldDTO();field.setId(504L);field.setChartId(201L);field.setDatasetGroupId(301L);
        org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{field});guard.checkField(point);
        field.setId(501L);assertThrows(SecurityException.class,()->guard.checkField(point));
        field.setId(503L);assertThrows(SecurityException.class,()->guard.checkField(point));
        org.mockito.Mockito.when(signature.getName()).thenReturn("delete");
        org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{502L});guard.checkField(point);
        org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{501L});assertThrows(SecurityException.class,()->guard.checkField(point));
    }
    @Test public void newlySavedChartCanPreviewWithTheEditorsZeroScenePlaceholder() {
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        var view=new io.dataease.extensions.view.dto.ChartViewDTO();view.setId(201L);view.setSceneId(0L);view.setTableId(301L);
        manage.requireChartPreview(view);
        view.setId(202L);assertThrows(SecurityException.class,()->manage.requireChartPreview(view));
    }
    @Test public void savingNewlyCreatedCanvasNormalizesZeroSceneAndRechecksChartOwnership() throws Throwable {
        jdbc.update("UPDATE de_local_department SET permissions='dashboard,dashboard_edit' WHERE id=?",mapper.account("operator").getDepartmentId());
        manage.validate(operatorToken());
        var guard=new io.dataease.localaccess.security.LocalDashboardGuard(manage);
        var point=org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);var signature=org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        org.mockito.Mockito.when(point.getSignature()).thenReturn(signature);org.mockito.Mockito.when(signature.getName()).thenReturn("updateCanvas");
        var request=new io.dataease.api.visualization.request.DataVisualizationBaseRequest();request.setId(101L);
        var view=new io.dataease.extensions.view.dto.ChartViewDTO();view.setId(201L);view.setSceneId(0L);view.setTableId(301L);
        request.setCanvasViewInfo(Map.of(201L,view));org.mockito.Mockito.when(point.getArgs()).thenReturn(new Object[]{request});guard.check(point);
        assertEquals(Long.valueOf(101L),view.getSceneId());
        view.setId(202L);view.setSceneId(0L);assertThrows(SecurityException.class,()->guard.check(point));
    }
}
