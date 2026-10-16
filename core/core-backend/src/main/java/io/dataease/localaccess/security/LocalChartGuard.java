package io.dataease.localaccess.security;

import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.localaccess.manage.LocalAccessManage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LocalChartGuard {
    private final LocalAccessManage manage;
    public LocalChartGuard(LocalAccessManage manage) { this.manage = manage; }
    @Around("execution(* io.dataease.chart.server.ChartDataServer.*(..))")
    public Object check(ProceedingJoinPoint point) throws Throwable {
        if (LocalAccessContext.get() != null) {
            for (Object argument : point.getArgs()) {
                if (argument instanceof io.dataease.api.chart.request.ChartExcelRequest export) {
                    ChartViewDTO view = export.getViewInfo();
                    if (view == null || view.getId() == null || !view.getId().toString().equals(export.getViewId()))
                        throw new SecurityException("导出图表不匹配");
                    manage.requireChart(view.getId(), view.getTableId());
                    if (export.getDvId() != null) manage.requireDashboard(Long.valueOf(export.getDvId()));
                    if (Boolean.TRUE.equals(view.getIsPlugin())) throw new SecurityException("本地租户模式暂不支持插件图表");
                }
                if (argument instanceof ChartViewDTO view) {
                    manage.requireChart(view.getId(), view.getTableId());
                    if (view.getSceneId() != null) manage.requireDashboard(view.getSceneId());
                    if (Boolean.TRUE.equals(view.getIsPlugin())) throw new SecurityException("本地租户模式暂不支持插件图表");
                }
            }
        }
        return point.proceed();
    }
}
