package io.dataease.api.localaccess;

import io.dataease.auth.vo.TokenVO;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

public interface LocalAccessApi {
    record Tenant(@JsonSerialize(using = ToStringSerializer.class) Long id, String code, String name, boolean enabled) { }
    record Department(@JsonSerialize(using = ToStringSerializer.class) Long id, @JsonSerialize(using = ToStringSerializer.class) Long tenantId, String name, Set<String> permissions, boolean enabled) { }
    record User(@JsonSerialize(using = ToStringSerializer.class) Long id, String account, String name, @JsonSerialize(using = ToStringSerializer.class) Long tenantId, @JsonSerialize(using = ToStringSerializer.class) Long departmentId,
                String dataUser, boolean enabled) { }
    record UserEditor(@JsonSerialize(using = ToStringSerializer.class) Long id, String account, String name, @JsonSerialize(using = ToStringSerializer.class) Long tenantId, @JsonSerialize(using = ToStringSerializer.class) Long departmentId,
                      String dataUser, boolean enabled, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) { }
    record Dashboard(@JsonSerialize(using = ToStringSerializer.class) Long id, String name, String type) { }
    record DatasetResource(@JsonSerialize(using = ToStringSerializer.class) Long id, String name) { }
    record DatasetGrant(@JsonSerialize(using = ToStringSerializer.class) Long tenantId, @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> datasetIds) { }
    record DatasourceResource(@JsonSerialize(using = ToStringSerializer.class) Long id, String name, String type) { }
    record DatasourceGrant(@JsonSerialize(using = ToStringSerializer.class) Long tenantId, @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> datasourceIds) { }
    record Grant(@JsonSerialize(using = ToStringSerializer.class) Long tenantId, @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> resourceIds) { }
    record PasswordChange(@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String pwd,
                          @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String newPwd) { }
    record Branding(String logoUrl) { }
    record Session(boolean enabled, boolean platformAdmin, @JsonSerialize(using = ToStringSerializer.class) Long tenantId, String tenantName,
                   Set<String> permissions, List<Tenant> tenants) { }
    @GetMapping("session") Session session();
    @PostMapping("switch/{tenantId}") TokenVO switchTenant(@PathVariable Long tenantId);
    @GetMapping("tenants") List<Tenant> tenants();
    @PostMapping("tenants") void saveTenant(@RequestBody Tenant tenant);
    @GetMapping("departments/{tenantId}") List<Department> departments(@PathVariable Long tenantId);
    @PostMapping("departments") void saveDepartment(@RequestBody Department department);
    @GetMapping("users/{tenantId}") List<User> users(@PathVariable Long tenantId);
    @GetMapping("users") List<User> filterUsers(@RequestParam(required = false) Long tenantId);
    @PostMapping("users") void saveUser(@RequestBody UserEditor user);
    @GetMapping("dashboards") List<Dashboard> dashboards();
    @GetMapping("grants/{tenantId}") Grant grants(@PathVariable Long tenantId);
    @PostMapping("grants") void saveGrants(@RequestBody Grant grant);
    @GetMapping("datasets") List<DatasetResource> datasets();
    @GetMapping("datasetGrants/{tenantId}") DatasetGrant datasetGrants(@PathVariable Long tenantId);
    @PostMapping("datasetGrants") void saveDatasetGrants(@RequestBody DatasetGrant grant);
    @GetMapping("datasources") List<DatasourceResource> datasources();
    @GetMapping("datasourceGrants/{tenantId}") DatasourceGrant datasourceGrants(@PathVariable Long tenantId);
    @PostMapping("datasourceGrants") void saveDatasourceGrants(@RequestBody DatasourceGrant grant);
    @PostMapping("password") void changePassword(@RequestBody PasswordChange request);
    @GetMapping("branding") Branding branding();
    @PostMapping("branding/logo") Branding uploadLogo(@RequestPart("file") MultipartFile file) throws java.io.IOException;
    @DeleteMapping("branding/logo") void resetLogo();
    @GetMapping("branding/image") ResponseEntity<byte[]> logoImage() throws java.io.IOException;
}
