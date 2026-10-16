package io.dataease.localaccess.security;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Applied before pagination/count queries, so unauthorized rows never reach the service. */
@Configuration
public class LocalResourcePermission {
    @Bean
    public static BeanPostProcessor localResourcePermissionInstaller() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof MybatisPlusInterceptor interceptor) {
                    var chain = new java.util.ArrayList<>(interceptor.getInterceptors());
                    chain.add(0, new DataPermissionInterceptor((MultiDataPermissionHandler) LocalResourcePermission::condition));
                    interceptor.setInterceptors(chain);
                }
                return bean;
            }
        };
    }
    static Expression condition(Table table, Expression where, String statementId) {
        var user = LocalAccessContext.get();
        if (user == null || statementId.startsWith("io.dataease.localaccess.")) return null;
        String name = table.getName().replace("`", "").toLowerCase(java.util.Locale.ROOT);
        String alias = table.getAlias() == null ? table.getFullyQualifiedName() : table.getAlias().getName();
        if (name.equals("core_export_task")) {
            String predicate = user.tenantId() == null ? "1=0" : "EXISTS (SELECT 1 FROM de_local_export_scope les WHERE les.task_id="
                    + alias + ".id AND les.user_id=" + user.userId() + " AND les.tenant_id=" + user.tenantId() + ")";
            try { return CCJSqlParserUtil.parseCondExpression(predicate); }
            catch (Exception e) { throw new SecurityException("无法应用导出租户权限", e); }
        }
        if (!user.permits("dataset") && java.util.Set.of("core_dataset_group", "core_dataset_table", "core_dataset_table_field").contains(name)) {
            String field = name.equals("core_dataset_group") ? "id" : "dataset_group_id";
            String condition = user.tenantId() == null ? "1=0" : alias + "." + field
                    + " IN (SELECT c.table_id FROM core_chart_view c JOIN de_local_dashboard_grant g ON c.scene_id=g.resource_id WHERE g.tenant_id=" + user.tenantId() + ")";
            try { return CCJSqlParserUtil.parseCondExpression(condition); }
            catch (Exception e) { throw new SecurityException("无法应用看板数据集权限", e); }
        }
        String column = switch (name) {
            case "data_visualization_info", "snapshot_data_visualization_info" -> "id";
            case "core_chart_view", "snapshot_core_chart_view", "snapshot_core_chart_view_v2" -> "scene_id";
            case "visualization_linkage" -> "dv_id";
            case "visualization_link_jump" -> "source_dv_id";
            case "visualization_outer_params" -> "visualization_id";
            default -> null;
        };
        if (column == null) return null;
        String predicate = "1=0";
        if (user.tenantId() != null && user.permits("dashboard")) {
            predicate = alias + "." + column + " IN (SELECT resource_id FROM de_local_dashboard_grant WHERE tenant_id=" + user.tenantId() + ")";
            if (name.equals("data_visualization_info") || name.equals("snapshot_data_visualization_info")) {
                // Folder metadata provides the path to assigned leaves; it never grants a leaf.
                predicate = "(" + predicate + " OR " + alias + ".node_type='folder')";
            }
        }
        try { return CCJSqlParserUtil.parseCondExpression(predicate); }
        catch (Exception e) { throw new SecurityException("无法应用租户资源权限", e); }
    }
}
