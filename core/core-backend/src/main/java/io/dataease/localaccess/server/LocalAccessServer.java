package io.dataease.localaccess.server;

import io.dataease.api.localaccess.LocalAccessApi;
import io.dataease.auth.vo.TokenVO;
import io.dataease.localaccess.manage.LocalAccessManage;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/localAccess")
public class LocalAccessServer implements LocalAccessApi {
    private final LocalAccessManage manage;
    public LocalAccessServer(LocalAccessManage manage) { this.manage = manage; }
    public Session session() { return manage.session(); }
    public TokenVO switchTenant(Long tenantId) { return manage.switchTenant(tenantId); }
    public List<Tenant> tenants() { return manage.tenants(); }
    public void saveTenant(Tenant tenant) { manage.saveTenant(tenant); }
    public List<Department> departments(Long tenantId) { return manage.departments(tenantId); }
    public void saveDepartment(Department department) { manage.saveDepartment(department); }
    public List<User> filterUsers(Long tenantId) { return manage.users(tenantId); }
    public List<User> users(Long tenantId) { return manage.users(tenantId); }
    public void saveUser(UserEditor user) { manage.saveUser(user); }
    public List<Dashboard> dashboards() { return manage.dashboards(); }
    public Grant grants(Long tenantId) { return manage.grants(tenantId); }
    public void saveGrants(Grant grant) { manage.saveGrants(grant); }
    public void changePassword(PasswordChange request) { manage.changePassword(request); }
}
