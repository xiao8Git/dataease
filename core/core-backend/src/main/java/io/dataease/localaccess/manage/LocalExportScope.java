package io.dataease.localaccess.manage;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.localaccess.dao.LocalAccessMapper;
import io.dataease.localaccess.dao.LocalAccessMapper.ExportScopeRow;
import io.dataease.localaccess.security.LocalAccessContext;
import io.dataease.utils.AuthUtils;
import org.springframework.stereotype.Component;

/** Durable tenant ownership; never restore authority from client-supplied export parameters. */
@Component
public class LocalExportScope {
    private final LocalAccessMapper mapper;
    private final LocalAccessManage access;
    public LocalExportScope(LocalAccessMapper mapper, LocalAccessManage access) { this.mapper = mapper; this.access = access; }
    public void create(String id, String type, Long resourceId) {
        var user = LocalAccessContext.get();
        if (user == null) return;
        user.requireTenant();
        ExportScopeRow task = new ExportScopeRow(id, user.userId(), user.tenantId(), type, resourceId);
        validate(task);
        mapper.insertExportScope(task);
    }
    public ExportScopeRow find(String id) { return mapper.exportScope(id); }
    private LocalAccessContext.Identity validate(ExportScopeRow task) {
        if (task == null) throw new SecurityException("导出任务不存在");
        var identity = access.exportIdentity(task.userId(), task.tenantId());
        var previous = LocalAccessContext.get();
        try {
            LocalAccessContext.set(identity);
            if ("chart".equals(task.resourceType())) access.requireChart(task.resourceId(), null);
            else if (!"dataset".equals(task.resourceType()) || !identity.permits("dataset"))
                throw new SecurityException("当前角色无权导出此资源");
            return identity;
        } finally { if (previous == null) LocalAccessContext.clear(); else LocalAccessContext.set(previous); }
    }
    public ExportScopeRow requireCurrent(String id) {
        var user = LocalAccessContext.require(); user.requireTenant();
        ExportScopeRow task = find(id);
        if (task == null || task.userId() != user.userId() || !task.tenantId().equals(user.tenantId()))
            throw new SecurityException("当前租户无权访问该导出任务");
        validate(task);
        return task;
    }
    public String downloadSecret(String id) {
        ExportScopeRow task = find(id);
        validate(task);
        return mapper.user(task.userId()).getTokenSecret();
    }
    public Context open(String id) {
        var identity = validate(find(id));
        Context context = new Context(LocalAccessContext.get(), AuthUtils.getUser());
        LocalAccessContext.set(identity);
        AuthUtils.setUser(new TokenUserBO(identity.userId(), identity.tenantId()));
        return context;
    }
    public static final class Context implements AutoCloseable {
        private final LocalAccessContext.Identity oldIdentity;
        private final TokenUserBO oldUser;
        Context(LocalAccessContext.Identity identity, TokenUserBO user) { oldIdentity = identity; oldUser = user; }
        @Override public void close() {
            if (oldIdentity == null) LocalAccessContext.clear(); else LocalAccessContext.set(oldIdentity);
            if (oldUser == null) AuthUtils.remove(); else AuthUtils.setUser(oldUser);
        }
    }
}
