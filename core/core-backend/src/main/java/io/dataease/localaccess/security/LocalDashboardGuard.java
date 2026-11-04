package io.dataease.localaccess.security;

import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.localaccess.manage.LocalAccessManage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LocalDashboardGuard {
    private final LocalAccessManage manage;
    public LocalDashboardGuard(LocalAccessManage manage) { this.manage = manage; }

    @Around("execution(* io.dataease.visualization.server.DataVisualizationServer.*(..)) || execution(* io.dataease.chart.server.ChartViewServer.*(..))")
    public Object check(ProceedingJoinPoint point) throws Throwable {
        var user = LocalAccessContext.get();
        if (user != null && !user.admin()) {
            String method = point.getSignature().getName();
            if ("deleteLogic".equals(method)) manage.requireDashboardOperation((Long) point.getArgs()[0], "dashboard_delete");
            for (Object arg : point.getArgs()) {
                if (arg instanceof DataVisualizationBaseRequest request) {
                    if ("saveCanvas".equals(method)) manage.requireDashboardCreation(request);
                    if ("nameCheck".equals(method)) {
                        manage.requireDashboardEditor();
                        if (request.getId() != null) manage.requireDashboardOperation(request.getId(), "dashboard_edit");
                    }
                    boolean edit = java.util.Set.of("updateCanvas", "updatePublishStatus", "recoverToPublished", "updateBase", "checkCanvasChange").contains(method)
                            || "findById".equals(method) && "main-edit".equals(request.getSource());
                    if (edit) {
                        manage.requireDashboardOperation(request.getId(), "dashboard_edit");
                        if (request.getCanvasViewInfo() != null) {
                            for (ChartViewDTO view : request.getCanvasViewInfo().values()) {
                                if (view.getSceneId() == null || view.getSceneId() <= 0) view.setSceneId(request.getId());
                                if (!request.getId().equals(view.getSceneId())) throw new SecurityException("图表不属于当前仪表板");
                                manage.requireEditableChart(view);
                            }
                        }
                    }
                }
                if (arg instanceof ChartViewDTO view) {
                    if ("listByDQ".equals(method)) {
                        manage.requireDataset((Long) point.getArgs()[0]);
                        manage.requireDashboardEditor();
                    } else manage.requireEditableChart(view);
                }
            }
            if ("copyField".equals(method)) { manage.requireDatasetField((Long) point.getArgs()[0]); manage.requireChartEdit((Long) point.getArgs()[1]); }
            if ("deleteField".equals(method)) manage.requireChartFieldEdit((Long) point.getArgs()[0]);
            if ("deleteFieldByChart".equals(method)) manage.requireChartEdit((Long) point.getArgs()[0]);
            if ("updateCheckVersion".equals(method)) manage.requireDashboardOperation((Long) point.getArgs()[0], "dashboard_edit");
        }
        return point.proceed();
    }
}
