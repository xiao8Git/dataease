package io.dataease.system.manage;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.exception.DEException;
import io.dataease.filter.FramePolicyProvider;
import io.dataease.filter.HtmlResourceFilter;
import io.dataease.i18n.Translator;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.dao.auto.mapper.CoreSysSettingMapper;
import io.dataease.utils.AuthUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class IframeWhitelistTest {
    private CoreSysSettingMapper mapper;
    private IframeWhitelistManage manage;
    private final AtomicReference<CoreSysSetting> toggle = new AtomicReference<>();
    private final AtomicReference<CoreSysSetting> stored = new AtomicReference<>();

    @Before
    public void setup() {
        new Translator().setMessageSource(new StaticMessageSource());
        new io.dataease.utils.IDUtils().setSnowFlake(new io.dataease.utils.SnowFlake(1, 1));
        mapper = mock(CoreSysSettingMapper.class);
        when(mapper.selectOne(any())).thenAnswer(call -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) call.getArgument(0);
            if (query == null) return null;
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue("security.iframeValidationEnabled") ? toggle.get() : stored.get();
        });
        when(mapper.insert(any(CoreSysSetting.class))).thenAnswer(call -> {
            CoreSysSetting row = call.getArgument(0);
            if ("security.iframeValidationEnabled".equals(row.getPkey())) toggle.set(row);
            else stored.set(row);
            return 1;
        });
        manage = new IframeWhitelistManage(mapper, "'self' https://legacy.example.com");
        var user = new TokenUserBO();
        user.setUserId(1L);
        AuthUtils.setUser(user);
    }

    @After
    public void cleanup() {
        AuthUtils.remove();
    }

    @Test
    public void normalizesExactOriginsAndDeduplicates() {
        assertEquals(List.of("https://portal.example.com", "http://127.0.0.1:15000", "http://[::1]:8100"),
                IframeOrigins.normalize(List.of(" HTTPS://PORTAL.EXAMPLE.COM:443/ ",
                        "https://portal.example.com", "http://127.0.0.1:15000", "http://[::1]:8100")));
        assertEquals("'self'", IframeOrigins.policy(List.of()));
    }

    @Test
    public void rejectsBroadSourcesInvalidPortsAndHeaderInjection() {
        for (String origin : List.of("*", "https://*.example.com", "https:", "//example.com", "'none'",
                "https://example.com/path", "https://example.com?x=1", "https://example.com#x",
                "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536",
                "https://example.com:", "https://example.com;", "https://example.com\r\nX-Test: x")) {
            assertThrows(origin, IllegalArgumentException.class, () -> IframeOrigins.normalize(List.of(origin)));
        }
        assertThrows(IllegalArgumentException.class, () -> IframeOrigins.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> IframeOrigins.normalize(Arrays.asList((String) null)));
        assertThrows(IllegalArgumentException.class, () -> IframeOrigins.normalize(java.util.Collections.nCopies(51, "https://example.com")));
    }

    @Test
    public void preservesDeploymentUntilFirstSaveAndPersistsEmptyOverride() {
        assertNull(manage.frameAncestors());
        assertFalse(manage.query().isManaged());
        assertEquals("'self' https://legacy.example.com", manage.query().getEffectivePolicy());
        manage.save(List.of("https://portal.example.com/"));
        assertEquals("https://portal.example.com", stored.get().getPval());
        var otherNode = new IframeWhitelistManage(mapper, "'self'");
        assertEquals("'self' https://portal.example.com", otherNode.frameAncestors());
        manage.save(List.of());
        assertTrue(manage.query().isManaged());
        assertEquals("'self'", manage.query().getEffectivePolicy());
        assertEquals("'self'", new IframeWhitelistManage(mapper, "'self' https://legacy.example.com").frameAncestors());
        verify(mapper).updateById(any(CoreSysSetting.class));
    }

    @Test
    public void deniesAnonymousAndNonAdminBeforeStorageAccess() {
        AuthUtils.remove();
        assertThrows(DEException.class, () -> manage.save(List.of("https://example.com")));
        assertThrows(DEException.class, () -> manage.query());
        var user = new TokenUserBO();
        user.setUserId(2L);
        AuthUtils.setUser(user);
        assertThrows(DEException.class, () -> manage.save(List.of()));
        assertThrows(DEException.class, () -> manage.query());
        assertThrows(DEException.class, () -> manage.save(List.of(), false));
        verifyNoInteractions(mapper);
    }

    @Test
    public void rejectsInvalidSaveWithoutChangingPersistedPolicy() {
        manage.save(List.of("https://portal.example.com"));
        assertThrows(IllegalArgumentException.class, () -> manage.save(List.of("*")));
        assertEquals(List.of("https://portal.example.com"), manage.query().getOrigins());
    }

    @Test
    public void failsClosedOnStorageErrorOrCorruptPolicy() {
        when(mapper.selectOne(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertEquals("'self'", manage.frameAncestors());
        reset(mapper);
        var setting = new CoreSysSetting();
        setting.setPval("https://example.com; frame-ancestors *");
        when(mapper.selectOne(any())).thenReturn(setting);
        assertEquals("'self'", new IframeWhitelistManage(mapper, "*").frameAncestors());
    }

    @Test
    public void refreshesRevocationsAcrossNodesWithinFiveSeconds() throws Exception {
        manage.save(List.of("https://portal.example.com"));
        assertEquals("'self' https://portal.example.com", manage.frameAncestors());
        var otherNode = new IframeWhitelistManage(mapper, "'self'");
        otherNode.save(List.of());
        Thread.sleep(5100);
        assertEquals("'self'", manage.frameAncestors());
    }

    private MockHttpServletResponse response(String managed, boolean providerPresent) throws Exception {
        var factory = new DefaultListableBeanFactory();
        if (providerPresent) {
            factory.registerSingleton("framePolicy", (FramePolicyProvider) () -> managed);
        }
        var filter = new HtmlResourceFilter();
        ReflectionTestUtils.setField(filter, "framePolicyProviders", factory.getBeanProvider(FramePolicyProvider.class));
        ReflectionTestUtils.setField(filter, "frameOptions", "SAMEORIGIN");
        ReflectionTestUtils.setField(filter, "frameAncestors", "'self' https://legacy.example.com");
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (request, res) -> {});
        return response;
    }

    @Test
    public void filterUsesManagedPolicyWithoutConflictingFrameOptions() throws Exception {
        var response = response("'self' https://portal.example.com", true);
        assertNull(response.getHeader("X-Frame-Options"));
        assertTrue(response.getHeader("Content-Security-Policy").endsWith("frame-ancestors 'self' https://portal.example.com"));
        assertEquals("SAMEORIGIN", response("'self'", true).getHeader("X-Frame-Options"));
    }

    @Test
    public void disablingValidationRetainsOriginsAndCanBeReenabled() throws Exception {
        assertTrue(manage.query().isEnabled());
        manage.save(List.of("https://portal.example.com"), false);
        assertFalse(manage.query().isEnabled());
        assertEquals("*", new IframeWhitelistManage(mapper, "'self'").frameAncestors());
        assertEquals(List.of("https://portal.example.com"), manage.query().getOrigins());
        var unrestricted = response("*", true);
        assertNull(unrestricted.getHeader("X-Frame-Options"));
        assertTrue(unrestricted.getHeader("Content-Security-Policy").endsWith("frame-ancestors *"));
        manage.save(List.of("https://portal.example.com"));
        assertFalse(manage.query().isEnabled()); // Old clients preserve the switch.
        manage.save(manage.query().getOrigins(), true);
        assertEquals("'self' https://portal.example.com", new IframeWhitelistManage(mapper, "'self'").frameAncestors());
    }

    @Test
    public void filterPreservesLegacyConfigurationWithNoManagedPolicy() throws Exception {
        for (boolean present : List.of(true, false)) {
            var response = response(null, present);
            assertEquals("SAMEORIGIN", response.getHeader("X-Frame-Options"));
            assertTrue(response.getHeader("Content-Security-Policy").endsWith("frame-ancestors 'self' https://legacy.example.com"));
        }
    }

    @Test
    public void persistsAndReloadsUsingRealMapper() throws Exception {
        var source = new org.apache.ibatis.datasource.unpooled.UnpooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:iframe_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            connection.createStatement().execute("CREATE TABLE core_sys_setting (id BIGINT PRIMARY KEY, "
                    + "pkey VARCHAR(255), pval CLOB NOT NULL, type VARCHAR(255), sort INT)");
        }
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        configuration.addMapper(CoreSysSettingMapper.class);
        var factory = new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(true)) {
            var realMapper = session.getMapper(CoreSysSettingMapper.class);
            var writer = new IframeWhitelistManage(realMapper, "'self'");
            writer.save(List.of("https://portal.example.com", "http://localhost:15000"));
            assertEquals(List.of("https://portal.example.com", "http://localhost:15000"),
                    new IframeWhitelistManage(realMapper, "'self'").query().getOrigins());
            writer.save(List.of());
            assertEquals("'self'", new IframeWhitelistManage(realMapper, "*").frameAncestors());
            writer.save(List.of("https://portal.example.com"), false);
            assertEquals("*", new IframeWhitelistManage(realMapper, "'self'").frameAncestors());
            writer.save(List.of("https://portal.example.com"), true);
            assertEquals("'self' https://portal.example.com", new IframeWhitelistManage(realMapper, "'self'").frameAncestors());
            assertEquals(Long.valueOf(2), realMapper.selectCount(null));
        }
    }

    @Test
    public void desktopMigrationPreservesSettingsAndSupportsLongAllowlist() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:iframe;MODE=MySQL")) {
            var statement = connection.createStatement();
            statement.execute("CREATE TABLE core_sys_setting (id BIGINT PRIMARY KEY, pval VARCHAR(255) NOT NULL)");
            statement.execute("INSERT INTO core_sys_setting VALUES (1, 'existing')");
            statement.execute(Files.readString(Path.of("src/main/resources/db/desktop/V2.10.27.2__iframe_whitelist.sql")));
            var rows = statement.executeQuery("SELECT pval FROM core_sys_setting WHERE id=1");
            assertTrue(rows.next());
            assertEquals("existing", rows.getString(1));
            try (var insert = connection.prepareStatement("INSERT INTO core_sys_setting VALUES (2, ?)")) {
                insert.setString(1, "https://portal.example.com\n".repeat(30));
                assertEquals(1, insert.executeUpdate());
            }
        }
    }
}
