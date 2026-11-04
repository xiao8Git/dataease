package io.dataease.localaccess.security;

import io.dataease.api.dataset.union.DatasetGroupInfoDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.localaccess.manage.LocalAccessManage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Validate existing resources before a DTO-based write or preview can produce side effects. */
@Aspect
@Component
public class LocalDatasetGuard {
    private final LocalAccessManage manage;
    public LocalDatasetGuard(LocalAccessManage manage) { this.manage = manage; }

    @Around("execution(* io.dataease.dataset.server.DatasetTreeServer.save(..)) || execution(* io.dataease.dataset.server.DatasetTreeServer.rename(..)) || execution(* io.dataease.dataset.server.DatasetTreeServer.create(..)) || execution(* io.dataease.dataset.server.DatasetTreeServer.move(..)) || execution(* io.dataease.dataset.server.DatasetTreeServer.delete(..)) || execution(* io.dataease.dataset.server.DatasetDataServer.previewData(..))")
    public Object check(ProceedingJoinPoint point) throws Throwable {
        if (LocalAccessContext.get() != null) {
            if ("delete".equals(point.getSignature().getName())) {
                manage.requireDatasetDeletion((Long) point.getArgs()[0]);
            }
            for (Object arg : point.getArgs()) {
                if (arg instanceof DatasetGroupInfoDTO dto) {
                    manage.requireDatasetSources(dto);
                    if (dto.getId() != null) {
                        var existing = manage.requireDataset(dto.getId());
                        dto.setCreateBy(existing.createBy());
                    }
                }
                if (arg instanceof DatasetTableFieldDTO field) {
                    manage.requireDatasetField(field.getId());
                    if (field.getDatasetGroupId() != null) manage.requireDataset(field.getDatasetGroupId());
                }
            }
        }
        return point.proceed();
    }

    @Around("execution(* io.dataease.dataset.server.DatasetFieldServer.save(..)) || execution(* io.dataease.dataset.server.DatasetFieldServer.delete(..)) || execution(* io.dataease.dataset.server.DatasetFieldServer.deleteByChartId(..))")
    public Object checkField(ProceedingJoinPoint point) throws Throwable {
        var user = LocalAccessContext.get();
        if (user != null && !user.admin()) {
            String method = point.getSignature().getName();
            if ("deleteByChartId".equals(method)) manage.requireChartEdit((Long) point.getArgs()[0]);
            else if ("delete".equals(method)) {
                if (user.permits("dataset")) manage.requireDatasetField((Long) point.getArgs()[0]);
                else manage.requireChartFieldEdit((Long) point.getArgs()[0]);
            } else {
                var field = (DatasetTableFieldDTO) point.getArgs()[0];
                if (field.getChartId() != null && field.getChartId() > 0) {
                    manage.requireChartEdit(field.getChartId());
                    manage.requireExistingChartFieldEdit(field.getId());
                } else if (!user.permits("dataset")) throw new SecurityException("当前角色不能修改数据集原始字段");
                manage.requireDatasetField(field.getId());
                if (field.getDatasetGroupId() != null) manage.requireDataset(field.getDatasetGroupId());
            }
        }
        return point.proceed();
    }
}
