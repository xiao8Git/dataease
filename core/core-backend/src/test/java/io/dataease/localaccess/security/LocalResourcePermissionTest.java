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
    private JdbcTemplate jdbc;
    @Before public void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE data_visualization_info(id BIGINT,node_type VARCHAR(20),name VARCHAR(30))");
        jdbc.execute("INSERT INTO data_visualization_info VALUES(10,'leaf','assigned'),(20,'leaf','secret')");
        jdbc.execute("CREATE TABLE de_local_dashboard_grant(tenant_id BIGINT,resource_id BIGINT)");
        jdbc.execute("INSERT INTO de_local_dashboard_grant VALUES(1,10),(2,20)");
        jdbc.execute("CREATE TABLE core_chart_view(id BIGINT,scene_id BIGINT,table_id BIGINT)");
        jdbc.execute("INSERT INTO core_chart_view VALUES(100,10,301),(200,20,302)");
        jdbc.execute("CREATE TABLE core_dataset_group(id BIGINT,node_type VARCHAR(20),create_by VARCHAR(64),name VARCHAR(30))");
        jdbc.execute("INSERT INTO core_dataset_group VALUES(301,'dataset','99','selected'),(302,'dataset','99','hidden'),(303,'dataset','1','own'),(304,'folder','99','folder')");
        jdbc.execute("CREATE TABLE de_local_dataset_grant(tenant_id BIGINT,dataset_id BIGINT)");
        jdbc.execute("CREATE TABLE core_datasource(id BIGINT,type VARCHAR(20),create_by VARCHAR(64),name VARCHAR(30))");
        jdbc.execute("INSERT INTO core_datasource VALUES(701,'Excel','99','selected'),(702,'mysql','1','hidden own'),(703,'folder','99','folder')");
        jdbc.execute("CREATE TABLE de_local_datasource_grant(tenant_id BIGINT,datasource_id BIGINT)");
        jdbc.execute("CREATE TABLE core_dataset_table(id BIGINT,dataset_group_id BIGINT)");
        jdbc.execute("INSERT INTO core_dataset_table VALUES(401,301),(402,302),(403,303)");
        jdbc.execute("CREATE TABLE core_dataset_table_field(id BIGINT,dataset_group_id BIGINT)");
        jdbc.execute("INSERT INTO core_dataset_table_field VALUES(501,301),(502,302),(503,303)");
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
        add("test.datasets","SELECT d.id FROM core_dataset_group d WHERE d.node_type='dataset' ORDER BY d.id",SqlCommandType.SELECT);
        add("test.datasetCount","SELECT COUNT(*) FROM core_dataset_group WHERE node_type='dataset'",SqlCommandType.SELECT);
        add("test.hiddenDetail","SELECT id FROM core_dataset_group WHERE id=302",SqlCommandType.SELECT);
        add("test.datasetTables","SELECT t.id FROM core_dataset_table t ORDER BY t.id",SqlCommandType.SELECT);
        add("test.datasetFields","SELECT f.id FROM core_dataset_table_field f ORDER BY f.id",SqlCommandType.SELECT);
        add("test.hiddenDatasetUpdate","UPDATE core_dataset_group SET name='hacked' WHERE id=302",SqlCommandType.UPDATE);
        add("test.datasources","SELECT s.id FROM core_datasource s WHERE s.type<>'folder' ORDER BY s.id",SqlCommandType.SELECT);
        add("test.sourceFolders","SELECT s.id FROM core_datasource s WHERE s.type='folder' ORDER BY s.id",SqlCommandType.SELECT);
        add("test.sourceCount","SELECT COUNT(*) FROM core_datasource WHERE type<>'folder'",SqlCommandType.SELECT);
        add("test.hiddenSource","SELECT id FROM core_datasource WHERE id=702",SqlCommandType.SELECT);
        add("test.sourceUpdate","UPDATE core_datasource SET name='hacked' WHERE id=702",SqlCommandType.UPDATE);
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
    private void datasetUser(long tenant,long userId,boolean admin) {
        LocalAccessContext.set(new LocalAccessContext.Identity(userId,"test","Test",admin,tenant,Long.toString(tenant),"Tenant","user",Set.of("dataset")));
        session.clearCache();
    }
    @Test public void emptySelectionPreservesAllDatasetsAndFields() {
        datasetUser(1L,1L,false);
        assertEquals(List.of(301L,302L,303L),session.selectList("test.datasets"));
        assertEquals(3L,((Long)session.selectOne("test.datasetCount")).longValue());
        assertEquals(List.of(501L,502L,503L),session.selectList("test.datasetFields"));
    }
    @Test public void datasourceEmptySelectionAllowsAllAndSelectionFiltersReadsCountsAndWrites() {
        datasetUser(1L,1L,false);
        assertEquals(List.of(701L,702L),session.selectList("test.datasources"));
        jdbc.execute("INSERT INTO de_local_datasource_grant VALUES(1,701)");session.clearCache();
        assertEquals(List.of(701L),session.selectList("test.datasources"));
        assertEquals(1L,((Long)session.selectOne("test.sourceCount")).longValue());
        assertTrue(session.selectList("test.hiddenSource").isEmpty());
        assertEquals(0,session.update("test.sourceUpdate"));
        assertEquals(List.of(703L),session.selectList("test.sourceFolders"));
        jdbc.execute("DELETE FROM de_local_datasource_grant");session.clearCache();
        assertEquals(List.of(701L,702L),session.selectList("test.datasources"));
    }
    @Test public void datasourceGrantsAreTenantScopedAndAdministratorIsUnrestricted() {
        jdbc.execute("INSERT INTO de_local_datasource_grant VALUES(1,701),(2,702)");
        datasetUser(2L,1L,false);assertEquals(List.of(702L),session.selectList("test.datasources"));
        datasetUser(1L,1L,true);assertEquals(List.of(701L,702L),session.selectList("test.datasources"));
        identity(null,false);assertTrue(session.selectList("test.datasources").isEmpty());
        identity(null,true);assertEquals(List.of(701L,702L),session.selectList("test.datasources"));
    }
    @Test public void selectionFiltersListsCountsDirectReadsTablesFieldsAndWrites() {
        jdbc.execute("INSERT INTO de_local_dataset_grant VALUES(1,301)");
        datasetUser(1L,1L,false);
        assertEquals(List.of(301L,303L),session.selectList("test.datasets"));
        assertEquals(2L,((Long)session.selectOne("test.datasetCount")).longValue());
        assertTrue(session.selectList("test.hiddenDetail").isEmpty());
        assertEquals(List.of(401L,403L),session.selectList("test.datasetTables"));
        assertEquals(List.of(501L,503L),session.selectList("test.datasetFields"));
        assertEquals(0,session.update("test.hiddenDatasetUpdate"));
        jdbc.execute("INSERT INTO core_dataset_group VALUES(305,'dataset','1','new own'),(306,'dataset','2','new other')");
        session.clearCache();
        assertEquals(List.of(301L,303L,305L),session.selectList("test.datasets"));
    }
    @Test public void configurationIsTenantScopedAndAdministratorsRemainUnrestricted() {
        jdbc.execute("INSERT INTO de_local_dataset_grant VALUES(1,301),(2,302)");
        datasetUser(2L,2L,false);
        assertEquals(List.of(302L),session.selectList("test.datasets"));
        datasetUser(1L,1L,true);
        assertEquals(List.of(301L,302L,303L),session.selectList("test.datasets"));
    }
    @Test public void dashboardOnlyMetadataStillRequiresDashboardGrantAndDatasetVisibility() {
        identity(1L,false);
        assertEquals(List.of(501L),session.selectList("test.datasetFields"));
        jdbc.execute("INSERT INTO de_local_dataset_grant VALUES(1,302)");
        session.clearCache();
        assertTrue(session.selectList("test.datasetFields").isEmpty());
    }
}
