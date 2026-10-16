package io.dataease.localaccess.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import java.util.*;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.Assert.*;

public class LocalResourcePermissionTest {
    private SqlSession session;
    private MybatisConfiguration configuration;
    @Before public void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE data_visualization_info(id BIGINT,node_type VARCHAR(20),name VARCHAR(30))");
        jdbc.execute("INSERT INTO data_visualization_info VALUES(10,'leaf','assigned'),(20,'leaf','secret')");
        jdbc.execute("CREATE TABLE de_local_dashboard_grant(tenant_id BIGINT,resource_id BIGINT)");
        jdbc.execute("INSERT INTO de_local_dashboard_grant VALUES(1,10),(2,20)");
        jdbc.execute("CREATE TABLE core_chart_view(id BIGINT,scene_id BIGINT)");
        jdbc.execute("INSERT INTO core_chart_view VALUES(100,10),(200,20)");
        jdbc.execute("CREATE TABLE visualization_link_jump(id BIGINT,source_dv_id BIGINT)");
        jdbc.execute("INSERT INTO visualization_link_jump VALUES(1000,10),(2000,20)");
        jdbc.execute("CREATE TABLE visualization_outer_params(params_id BIGINT,visualization_id BIGINT)");
        jdbc.execute("INSERT INTO visualization_outer_params VALUES(1000,10),(2000,20)");
        configuration=new MybatisConfiguration(new Environment("test",new JdbcTransactionFactory(),ds));
        var interceptor=new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor((MultiDataPermissionHandler)LocalResourcePermission::condition));
        configuration.addInterceptor(interceptor);
        add("test.list","SELECT d.id FROM data_visualization_info d ORDER BY d.id",SqlCommandType.SELECT);
        add("test.count","SELECT COUNT(*) FROM data_visualization_info d",SqlCommandType.SELECT);
        add("test.chart","SELECT id FROM core_chart_view",SqlCommandType.SELECT);
        add("test.jump","SELECT j.id FROM visualization_link_jump j",SqlCommandType.SELECT);
        add("test.outer","SELECT p.params_id FROM visualization_outer_params p",SqlCommandType.SELECT);
        add("test.update","UPDATE data_visualization_info SET name='hacked' WHERE id=20",SqlCommandType.UPDATE);
        session=new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
    }
    private void add(String id,String sql,SqlCommandType type) {
        var result=new ResultMap.Builder(configuration,id+"Result",Long.class,List.of()).build();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration,id,new StaticSqlSource(configuration,sql),type).resultMaps(List.of(result)).build());
    }
    private void identity(Long tenant,boolean admin) {
        LocalAccessContext.set(new LocalAccessContext.Identity(1,"test","Test",admin,tenant,tenant==null?null:tenant.toString(),"Tenant","user",Set.of("dashboard")));
        session.clearCache();
    }
    @After public void cleanup(){LocalAccessContext.clear();session.close();}
    @Test public void listCountAndChartQueriesUseOnlySelectedTenant() {
        identity(1L,false); assertEquals(List.of(10L),session.selectList("test.list"));
        assertEquals(1L,((Long)session.selectOne("test.count")).longValue());
        assertEquals(List.of(100L),session.selectList("test.chart"));
        identity(2L,true); assertEquals(List.of(20L),session.selectList("test.list"));
        assertEquals(List.of(200L),session.selectList("test.chart"));
    }
    @Test public void administratorWithoutSelectionCannotListResources() {
        identity(null,true);assertTrue(session.selectList("test.list").isEmpty());
    }
    @Test public void jumpAndOuterParametersUseTheirActualResourceColumns() {
        identity(1L,false);
        assertEquals(List.of(1000L),session.selectList("test.jump"));
        assertEquals(List.of(1000L),session.selectList("test.outer"));
        identity(2L,true);
        assertEquals(List.of(2000L),session.selectList("test.jump"));
        assertEquals(List.of(2000L),session.selectList("test.outer"));
    }
    @Test public void directUpdateOfUnassignedDashboardAffectsNoRows() {
        identity(1L,true);assertEquals(0,session.update("test.update"));
    }
}
