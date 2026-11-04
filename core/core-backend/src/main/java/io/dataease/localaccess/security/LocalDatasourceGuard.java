package io.dataease.localaccess.security;

import io.dataease.api.dataset.dto.PreviewSqlDTO;
import io.dataease.api.ds.vo.BusiDsRequest;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.extensions.datasource.dto.DatasetTableDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldRequest;
import io.dataease.localaccess.manage.LocalAccessManage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Check source selection before opening a connection, returning cached fields or saving SQL logs. */
@Aspect
@Component
public class LocalDatasourceGuard {
    private final LocalAccessManage manage;
    private final DataSourceManage sources;
    public LocalDatasourceGuard(LocalAccessManage manage, DataSourceManage sources) {
        this.manage = manage; this.sources = sources;
    }
    @Around("execution(* io.dataease.datasource.server.DatasourceServer.getTables(..)) || execution(* io.dataease.datasource.server.DatasourceServer.getTableField(..)) || execution(* io.dataease.datasource.server.DatasourceServer.getSchema(..)) || execution(* io.dataease.dataset.server.DatasetDataServer.tableField(..)) || execution(* io.dataease.dataset.server.DatasetDataServer.previewSql*(..))")
    public Object check(ProceedingJoinPoint point) throws Throwable {
        var user = LocalAccessContext.get();
        if (user != null && !user.admin()) {
            for (Object arg : point.getArgs()) {
                if (arg instanceof DatasetTableDTO table) manage.requireDatasource(table.getDatasourceId());
                if (arg instanceof DatasetTableFieldRequest field) manage.requireDatasource(field.getDatasourceId());
                if (arg instanceof PreviewSqlDTO sql) manage.requireDatasource(sql.getDatasourceId());
                if (arg instanceof BusiDsRequest request) {
                    manage.requireDatasource(request.getId());
                    var stored = sources.getCoreDatasource(request.getId());
                    if (stored == null) throw new SecurityException("数据源不存在或无访问权限");
                    request.setType(stored.getType());
                    request.setConfiguration(Base64.getEncoder().encodeToString(stored.getConfiguration().getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        return point.proceed();
    }
}
