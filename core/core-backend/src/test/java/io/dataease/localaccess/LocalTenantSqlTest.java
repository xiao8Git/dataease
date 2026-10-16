package io.dataease.localaccess;

import io.dataease.localaccess.security.LocalTenantSql;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.Assert.*;

public class LocalTenantSqlTest {
    private JdbcTemplate jdbc;
    @Before public void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE facts(id INT, tenant_id VARCHAR(20), amount INT)");
        jdbc.execute("INSERT INTO facts VALUES(1,'a',10),(1,'b',900),(2,'a',20),(3,'b',800)");
        jdbc.execute("CREATE TABLE labels(id INT, tenant_id VARCHAR(20), label VARCHAR(20))");
        jdbc.execute("INSERT INTO labels VALUES(1,'a','a-only'),(1,'b','secret'),(3,'b','secret-three')");
    }
    @Test public void filtersBeforeAggregationEvenWithoutUserPredicate() {
        assertEquals(30, jdbc.queryForObject(LocalTenantSql.rewrite("SELECT SUM(amount) FROM facts", "a"), Integer.class).intValue());
        assertEquals(1700, jdbc.queryForObject(LocalTenantSql.rewrite("SELECT SUM(amount) FROM facts", "b"), Integer.class).intValue());
    }
    @Test public void filtersEveryJoinIncludingNullableSide() {
        var rows = jdbc.queryForList(LocalTenantSql.rewrite("SELECT f.id,l.label FROM facts f LEFT JOIN labels l ON l.id=f.id ORDER BY f.id", "a"));
        assertEquals(2, rows.size()); assertEquals("a-only", rows.get(0).get("LABEL")); assertNull(rows.get(1).get("LABEL"));
    }
    @Test public void nestedSubqueriesUnionAndOrCannotEscapeTenant() {
        String sql = "SELECT amount FROM facts WHERE tenant_id='b' OR 1=1 UNION ALL SELECT amount FROM (SELECT * FROM facts) nested";
        var values = jdbc.queryForList(LocalTenantSql.rewrite(sql, "a"), Integer.class);
        assertEquals(4, values.size()); assertFalse(values.contains(900)); assertFalse(values.contains(800));
    }
    @Test public void correlatedSubqueryCannotReadOtherTenant() {
        String sql = "SELECT f.id FROM facts f WHERE EXISTS(SELECT 1 FROM labels l WHERE l.id=f.id AND l.label LIKE 'secret%')";
        assertTrue(jdbc.queryForList(LocalTenantSql.rewrite(sql, "a")).isEmpty());
    }
    @Test public void rejectsNonSelectMultipleStatementsExternalFunctionsAndCte() {
        for (String sql : new String[]{"DELETE FROM facts", "SELECT * FROM facts; SELECT * FROM labels", "SELECT load_file('/etc/passwd')", "WITH x AS (SELECT * FROM facts) SELECT * FROM x"})
            assertThrows(SecurityException.class, () -> LocalTenantSql.rewrite(sql, "a"));
        assertThrows(IllegalArgumentException.class, () -> LocalTenantSql.rewrite("SELECT * FROM facts", "a' OR 1=1"));
    }
    @Test public void missingTenantColumnFailsInsteadOfReturningUnscopedData() {
        jdbc.execute("CREATE TABLE unscoped(secret VARCHAR(20))");
        jdbc.execute("INSERT INTO unscoped VALUES('secret')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.queryForList(LocalTenantSql.rewrite("SELECT * FROM unscoped", "a")));
    }
}
