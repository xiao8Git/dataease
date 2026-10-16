package io.dataease.localaccess.dao;

import io.dataease.api.localaccess.LocalAccessApi.*;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LocalAccessMapper {
    record ExportScopeRow(String taskId, Long userId, Long tenantId, String resourceType, Long resourceId) { }
    @Select("SELECT task_id,user_id,tenant_id,resource_type,resource_id FROM de_local_export_scope WHERE task_id=#{id}")
    ExportScopeRow exportScope(String id);
    @Insert("INSERT INTO de_local_export_scope(task_id,user_id,tenant_id,resource_type,resource_id) VALUES(#{taskId},#{userId},#{tenantId},#{resourceType},#{resourceId})")
    void insertExportScope(ExportScopeRow task);
    @Data class Account {
        private Long id;
        private String account;
        private String name;
        private String passwordHash;
        private String tokenSecret;
        private Long tenantId;
        private Long departmentId;
        private String dataUser;
        private boolean platformAdmin;
        private boolean enabled;
    }
    @Data class DepartmentRow {
        private Long id;
        private Long tenantId;
        private String name;
        private String permissions;
        private boolean enabled;
    }
    @Select("SELECT * FROM de_local_user WHERE account = #{account}") Account account(String account);
    @Select("SELECT * FROM de_local_user WHERE id = #{id}") Account user(long id);
    @Select("SELECT * FROM de_local_department WHERE id = #{id}") DepartmentRow department(long id);
    @Select("SELECT id, code, name, enabled FROM de_local_tenant WHERE id = #{id}") Tenant tenant(long id);
    @Select("SELECT id, code, name, enabled FROM de_local_tenant ORDER BY name, id") List<Tenant> tenants();
    @Insert("INSERT INTO de_local_tenant(id,code,name,enabled) VALUES(#{id},#{code},#{name},#{enabled})") void insertTenant(Tenant row);
    @Update("UPDATE de_local_tenant SET name=#{name}, enabled=#{enabled} WHERE id=#{id}") int updateTenant(Tenant row);
    @Select("SELECT * FROM de_local_department WHERE tenant_id=#{tenantId} ORDER BY name,id") List<DepartmentRow> departments(long tenantId);
    @Insert("INSERT INTO de_local_department(id,tenant_id,name,permissions,enabled) VALUES(#{id},#{tenantId},#{name},#{permissions},#{enabled})") void insertDepartment(DepartmentRow row);
    @Update("UPDATE de_local_department SET name=#{name},permissions=#{permissions},enabled=#{enabled} WHERE id=#{id} AND tenant_id=#{tenantId}") int updateDepartment(DepartmentRow row);
    @Select("<script>SELECT * FROM de_local_user WHERE <choose><when test='tenantId == null'>tenant_id IS NULL AND platform_admin=TRUE</when><otherwise>tenant_id=#{tenantId} AND platform_admin=FALSE</otherwise></choose> ORDER BY account</script>")
    List<Account> users(@Param("tenantId") Long tenantId);
    @Insert("INSERT INTO de_local_user(id,account,name,password_hash,token_secret,tenant_id,department_id,data_user,platform_admin,enabled) VALUES(#{id},#{account},#{name},#{passwordHash},#{tokenSecret},#{tenantId},#{departmentId},#{dataUser},#{platformAdmin},#{enabled})") void insertUser(Account row);
    @Update("UPDATE de_local_user SET name=#{name},password_hash=#{passwordHash},token_secret=#{tokenSecret},tenant_id=#{tenantId},department_id=#{departmentId},data_user=#{dataUser},enabled=#{enabled} WHERE id=#{id} AND platform_admin=FALSE") int updateUser(Account row);
    @Update("UPDATE de_local_user SET token_secret=#{secret} WHERE id=#{id}") void revoke(@Param("id") long id, @Param("secret") String secret);
    @Update("UPDATE de_local_user SET password_hash=#{hash},token_secret=#{secret} WHERE id=#{id}") void password(@Param("id") long id, @Param("hash") String hash, @Param("secret") String secret);
    @Select("SELECT language FROM de_local_user WHERE id=#{id}") String language(long id);
    @Update("UPDATE de_local_user SET language=#{language} WHERE id=#{id}") void updateLanguage(@Param("id") long id, @Param("language") String language);
    @Select("SELECT id, name, type FROM data_visualization_info WHERE node_type='leaf' AND delete_flag=FALSE AND pid<>-1 ORDER BY name,id") List<Dashboard> dashboards();
    @Select("SELECT resource_id FROM de_local_dashboard_grant WHERE tenant_id=#{tenantId} ORDER BY resource_id") List<Long> grants(long tenantId);
    @Delete("DELETE FROM de_local_dashboard_grant WHERE tenant_id=#{tenantId}") void clearGrants(long tenantId);
    @Insert("INSERT INTO de_local_dashboard_grant(tenant_id,resource_id) VALUES(#{tenantId},#{resourceId})") void insertGrant(@Param("tenantId") long tenantId, @Param("resourceId") long resourceId);
    @Select("SELECT COUNT(*) FROM de_local_dashboard_grant WHERE tenant_id=#{tenantId} AND resource_id=#{resourceId}") int hasGrant(@Param("tenantId") long tenantId, @Param("resourceId") long resourceId);
    @Select("SELECT scene_id FROM core_chart_view WHERE id=#{id}") Long chartResource(long id);
    @Select("SELECT table_id FROM core_chart_view WHERE id=#{id}") Long chartDataset(long id);
}
