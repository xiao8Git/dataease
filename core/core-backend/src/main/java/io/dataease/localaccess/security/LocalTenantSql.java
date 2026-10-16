package io.dataease.localaccess.security;

import io.dataease.extensions.datasource.dto.QueryScope;
import io.dataease.share.manage.TicketScopeSql;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import org.springframework.stereotype.Component;

/** Filters physical tables BEFORE joins and aggregation, including nested queries and UNION. */
@Component
public class LocalTenantSql {
    @jakarta.annotation.PostConstruct
    public void install() { QueryScope.install(this::scopeQuery); }
    public String scopeQuery(String sql) {
        var identity = LocalAccessContext.get();
        if (identity == null) return sql;
        identity.requireTenant();
        return rewrite(sql, identity.tenantCode());
    }
    public static String rewrite(String sql, String tenant) {
        TicketScopeSql.identity(tenant);
        try {
            var statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) throw new SecurityException("租户查询仅允许 SELECT");
            StringBuilder buffer = new StringBuilder();
            ExpressionDeParser expressions = new ExpressionDeParser() {
                @Override public void visit(Function function) {
                    String name = function.getName().toLowerCase(java.util.Locale.ROOT);
                    if (java.util.Set.of("load_file", "sleep", "benchmark", "dblink", "query", "read_csv", "read_parquet").contains(name))
                        throw new SecurityException("租户查询不支持外部访问函数");
                    super.visit(function);
                }
            };
            SelectDeParser selects = new SelectDeParser(expressions, buffer) {
                @Override public void visit(Table table) {
                    if (table.getPivot() != null || table.getUnPivot() != null) throw new SecurityException("租户查询暂不支持 PIVOT");
                    String alias = table.getAlias() == null ? table.getName() : table.getAlias().getName();
                    buffer.append("(SELECT * FROM ").append(table.getFullyQualifiedName())
                            .append(" WHERE tenant_id = '").append(tenant).append("') ").append(alias);
                }
                @Override public void visit(PlainSelect plain) {
                    if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) throw new SecurityException("租户查询禁止 SELECT INTO");
                    if (plain.getWithItemsList() != null && !plain.getWithItemsList().isEmpty()) throw new SecurityException("请将 CTE 改写为子查询后使用租户查询");
                    super.visit(plain);
                }
                @Override public void visit(WithItem item) { throw new SecurityException("租户查询暂不支持 CTE"); }
                @Override public void visit(TableFunction table) { throw new SecurityException("租户查询不支持表函数"); }
                @Override public void visit(TableStatement table) { throw new SecurityException("租户查询不支持 TABLE 语句"); }
            };
            expressions.setSelectVisitor(selects); expressions.setBuffer(buffer);
            if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) throw new SecurityException("租户查询暂不支持 CTE");
            select.accept(selects);
            return buffer.toString();
        } catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new SecurityException("无法安全应用租户过滤，请检查 SQL 和 tenant_id 字段", e); }
    }
}
