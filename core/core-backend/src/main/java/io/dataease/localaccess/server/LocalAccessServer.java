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
    @jakarta.annotation.Resource
    private io.dataease.localaccess.manage.LocalBrandingManage branding;
    private final LocalAccessManage manage;
    public LocalAccessServer(LocalAccessManage manage) { this.manage = manage; }
    @Override public Branding branding() { return branding.get(); }
    @Override public Branding uploadLogo(org.springframework.web.multipart.MultipartFile file) throws java.io.IOException { return branding.upload(file); }
    @Override public void resetLogo() { branding.reset(); }
    @Override public org.springframework.http.ResponseEntity<byte[]> logoImage() throws java.io.IOException {
        byte[] image = branding.image();
        return image == null ? org.springframework.http.ResponseEntity.notFound().build()
                : org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(image);
    }
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
    public List<DatasetResource> datasets() { return manage.datasets(); }
    public DatasetGrant datasetGrants(Long tenantId) { return manage.datasetGrants(tenantId); }
    public void saveDatasetGrants(DatasetGrant grant) { manage.saveDatasetGrants(grant); }
    public List<DatasourceResource> datasources() { return manage.datasources(); }
    public DatasourceGrant datasourceGrants(Long tenantId) { return manage.datasourceGrants(tenantId); }
    public void saveDatasourceGrants(DatasourceGrant grant) { manage.saveDatasourceGrants(grant); }
    public void changePassword(PasswordChange request) { manage.changePassword(request); }
}
