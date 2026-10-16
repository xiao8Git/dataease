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
        jdbc.execute("CREATE TABLE data_visualization_info(id BIGINT, name VARCHAR(50),type VARCHAR(20),node_type VARCHAR(20),delete_flag BOOLEAN,pid BIGINT)");
        jdbc.execute("INSERT INTO data_visualization_info VALUES(101,'shared','dashboard','leaf',FALSE,0),(102,'other','dashboard','leaf',FALSE,0)");
        jdbc.execute("CREATE TABLE core_chart_view(id BIGINT, scene_id BIGINT,table_id BIGINT)");
        jdbc.execute("INSERT INTO core_chart_view VALUES(201,101,301),(202,102,302)");
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
}
