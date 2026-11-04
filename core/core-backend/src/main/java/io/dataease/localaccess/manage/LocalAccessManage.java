package io.dataease.localaccess.manage;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.dataease.api.localaccess.LocalAccessApi.*;
import io.dataease.auth.LocalIdentityProvider;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.auth.vo.TokenVO;
import io.dataease.localaccess.dao.LocalAccessMapper;
import io.dataease.localaccess.dao.LocalAccessMapper.Account;
import io.dataease.localaccess.dao.LocalAccessMapper.DepartmentRow;
import io.dataease.localaccess.security.LocalAccessContext;
import io.dataease.localaccess.security.LocalAccessContext.Identity;
import io.dataease.localaccess.security.LocalPassword;
import io.dataease.share.manage.TicketScopeSql;
import io.dataease.utils.IDUtils;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LocalAccessManage implements LocalIdentityProvider, ApplicationRunner {
    public static final Set<String> FEATURES = Set.of("dashboard", "dataset", "dashboard_edit", "dashboard_delete", "dataset_delete");
    private static final String ISSUER = "dataease-local-access";
    private final LocalAccessMapper mapper;
    private final boolean enabled;
    private final String bootstrapPassword;

    public LocalAccessManage(LocalAccessMapper mapper,
                             @Value("${dataease.local-access.enabled:false}") boolean enabled,
                             @Value("${dataease.local-access.admin-password:}") String bootstrapPassword) {
        this.mapper = mapper; this.enabled = enabled; this.bootstrapPassword = bootstrapPassword;
    }
    @Override public boolean enabled() { return enabled; }
    @Override public void clear() { LocalAccessContext.clear(); }
    @Override public void run(ApplicationArguments arguments) {
        if (!enabled || mapper.user(1L) != null) return;
        Account admin = new Account();
        admin.setId(1L); admin.setAccount("admin"); admin.setName("平台管理员");
        admin.setPasswordHash(LocalPassword.hash(bootstrapPassword));
        admin.setTokenSecret(LocalPassword.secret()); admin.setDataUser("admin");
        admin.setPlatformAdmin(true); admin.setEnabled(true);
        mapper.insertUser(admin);
    }
    public TokenVO login(String name, String password) {
        if (!enabled) throw new SecurityException("本地权限管理未启用");
        Account account = mapper.account(name);
        if (account == null || !account.isEnabled() || !LocalPassword.matches(password, account.getPasswordHash()))
            throw new SecurityException("用户名或密码错误");
        Long tenantId = account.isPlatformAdmin() ? null : account.getTenantId();
        identity(account, tenantId);
        return issue(account, tenantId);
    }
    private TokenVO issue(Account account, Long tenantId) {
        Instant expires = Instant.now().plusSeconds(8 * 3600);
        String token = JWT.create().withIssuer(ISSUER).withSubject(account.getId().toString())
                .withClaim("uid", account.getId()).withClaim("oid", tenantId == null ? 0L : tenantId)
                .withIssuedAt(Date.from(Instant.now())).withExpiresAt(Date.from(expires))
                .sign(Algorithm.HMAC256(account.getTokenSecret()));
        return new TokenVO(token, expires.toEpochMilli());
    }
    @Override public TokenUserBO validate(String token) {
        clear();
        if (token == null || token.length() > 4096) throw new SecurityException("登录已失效");
        var decoded = JWT.decode(token);
        Long uid = decoded.getClaim("uid").asLong();
        if (uid == null) throw new SecurityException("登录已失效");
        Account account = mapper.user(uid);
        if (account == null || !account.isEnabled()) throw new SecurityException("账号已停用");
        var verified = JWT.require(Algorithm.HMAC256(account.getTokenSecret())).withIssuer(ISSUER)
                .withSubject(uid.toString()).build().verify(token);
        if (verified.getExpiresAt() == null) throw new SecurityException("登录已失效");
        Long oid = verified.getClaim("oid").asLong();
        if (oid == null) throw new SecurityException("租户无效");
        LocalAccessContext.set(identity(account, oid == 0 ? null : oid));
        return new TokenUserBO(uid, oid);
    }
    public Identity exportIdentity(long userId, Long tenantId) {
        Account account = mapper.user(userId);
        if (!enabled || account == null || !account.isEnabled() || tenantId == null)
            throw new SecurityException("导出账号或租户已失效");
        return identity(account, tenantId);
    }
    private Identity identity(Account account, Long tenantId) {
        Set<String> permissions = FEATURES;
        if (!account.isPlatformAdmin()) {
            if (tenantId == null || !tenantId.equals(account.getTenantId())) throw new SecurityException("租户无效");
            DepartmentRow department = mapper.department(account.getDepartmentId());
            if (department == null || !department.isEnabled() || !tenantId.equals(department.getTenantId()))
                throw new SecurityException("角色已停用");
            permissions = new HashSet<>(permissions(department.getPermissions()));
            permissions.retainAll(FEATURES);
        }
        Tenant tenant = tenantId == null ? null : mapper.tenant(tenantId);
        if (tenantId != null && (tenant == null || !tenant.enabled())) throw new SecurityException("租户已停用");
        return new Identity(account.getId(), account.getAccount(), account.getName(), account.isPlatformAdmin(), tenantId,
                tenant == null ? null : tenant.code(), tenant == null ? null : tenant.name(), account.getDataUser(), permissions);
    }
    private Set<String> permissions(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.copyOf(Arrays.asList(value.split(",")));
    }
    public Session session() {
        if (!enabled) return new Session(false, false, null, null, Set.of(), List.of());
        var user = LocalAccessContext.require();
        List<Tenant> tenants = user.admin() ? mapper.tenants().stream().filter(Tenant::enabled).toList()
                : List.of(mapper.tenant(user.tenantId()));
        return new Session(true, user.admin(), user.tenantId(), user.tenantName(), user.permissions(), tenants);
    }
    public TokenVO switchTenant(Long tenantId) {
        if (Long.valueOf(0).equals(tenantId)) tenantId = null;
        var current = LocalAccessContext.require();
        if (!current.admin() && !Objects.equals(current.tenantId(), tenantId)) throw new SecurityException("不可切换其他租户");
        Account account = mapper.user(current.userId());
        identity(account, tenantId);
        // Rotate secret so another tab cannot keep using the previous tenant token.
        account.setTokenSecret(LocalPassword.secret()); mapper.revoke(account.getId(), account.getTokenSecret());
        return issue(account, tenantId);
    }
    public void logout() {
        var user = LocalAccessContext.require(); mapper.revoke(user.userId(), LocalPassword.secret());
    }
    public TokenVO refresh() {
        var user = LocalAccessContext.require();
        return issue(mapper.user(user.userId()), user.tenantId());
    }
    @Transactional public void changePassword(PasswordChange request) {
        var current = LocalAccessContext.require();
        Account account = mapper.user(current.userId());
        String oldPassword = io.dataease.utils.RsaUtils.decryptStr(request.pwd());
        String newPassword = io.dataease.utils.RsaUtils.decryptStr(request.newPwd());
        if (!LocalPassword.matches(oldPassword, account.getPasswordHash())) throw new SecurityException("原密码错误");
        if (Objects.equals(oldPassword, newPassword)) throw new IllegalArgumentException("新密码不能与原密码相同");
        mapper.password(current.userId(), LocalPassword.hash(newPassword), LocalPassword.secret());
    }
    public String language() { return mapper.language(LocalAccessContext.require().userId()); }
    public void language(String value) {
        if (!Set.of("zh-CN", "en", "tw").contains(value)) throw new IllegalArgumentException("无效语言");
        mapper.updateLanguage(LocalAccessContext.require().userId(), value);
    }
    public void requireAdmin() {
        if (!enabled || !LocalAccessContext.require().admin()) throw new SecurityException("仅平台管理员可管理权限");
    }
    private String name(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException("名称长度必须为 1 至 128 个字符");
        return value.trim();
    }
    public List<Tenant> tenants() { requireAdmin(); return mapper.tenants(); }
    @Transactional public void saveTenant(Tenant row) {
        requireAdmin(); String code = TicketScopeSql.identity(row.code());
        if (row.id() == null) mapper.insertTenant(new Tenant(IDUtils.snowID(), code, name(row.name()), row.enabled()));
        else {
            Tenant existing = mapper.tenant(row.id());
            if (existing == null || !existing.code().equals(code)) throw new IllegalArgumentException("租户标识不可修改");
            mapper.updateTenant(new Tenant(row.id(), code, name(row.name()), row.enabled()));
        }
    }
    public List<Department> departments(long tenantId) {
        requireAdmin(); return mapper.departments(tenantId).stream().map(row -> new Department(row.getId(), row.getTenantId(),
                row.getName(), permissions(row.getPermissions()), row.isEnabled())).toList();
    }
    @Transactional public void saveDepartment(Department dto) {
        requireAdmin();
        if (dto.tenantId() == null || mapper.tenant(dto.tenantId()) == null) throw new IllegalArgumentException("租户不存在");
        if (dto.permissions() == null || !FEATURES.containsAll(dto.permissions())) throw new IllegalArgumentException("无效功能权限");
        if ((dto.permissions().contains("dashboard_edit") || dto.permissions().contains("dashboard_delete")) && !dto.permissions().contains("dashboard")
                || dto.permissions().contains("dataset_delete") && !dto.permissions().contains("dataset"))
            throw new IllegalArgumentException("请同时选择对应的仪表板或数据集功能权限");
        DepartmentRow row = new DepartmentRow(); row.setId(dto.id() == null ? IDUtils.snowID() : dto.id());
        row.setTenantId(dto.tenantId()); row.setName(name(dto.name())); row.setEnabled(dto.enabled());
        row.setPermissions(String.join(",", new TreeSet<>(dto.permissions())));
        if (dto.id() == null) mapper.insertDepartment(row);
        else if (mapper.updateDepartment(row) != 1) throw new IllegalArgumentException("角色不存在或租户不匹配");
    }
    public List<User> users(Long tenantId) {
        requireAdmin(); return mapper.users(tenantId).stream().map(row -> new User(row.getId(), row.getAccount(), row.getName(),
                row.getTenantId(), row.getDepartmentId(), row.getDataUser(), row.isEnabled())).toList();
    }
    @Transactional public void saveUser(UserEditor dto) {
        requireAdmin();
        if (dto.account() == null || !dto.account().matches("[A-Za-z0-9_.@-]{1,64}")) throw new IllegalArgumentException("无效账号");
        if (dto.tenantId() == null || mapper.tenant(dto.tenantId()) == null) throw new IllegalArgumentException("普通用户必须选择租户");
        DepartmentRow department = dto.departmentId() == null ? null : mapper.department(dto.departmentId());
        if (department == null || !Objects.equals(department.getTenantId(), dto.tenantId())) throw new IllegalArgumentException("角色必须属于用户租户");
        Account row = dto.id() == null ? new Account() : mapper.user(dto.id());
        if (row == null || row.isPlatformAdmin()) throw new IllegalArgumentException("不可修改平台管理员");
        if (dto.id() != null && (!dto.account().equals(row.getAccount()) || !dto.tenantId().equals(row.getTenantId())))
            throw new IllegalArgumentException("账号和绑定租户不可修改，请创建新账号");
        row.setId(dto.id() == null ? IDUtils.snowID() : dto.id()); row.setAccount(dto.account()); row.setName(name(dto.name()));
        row.setTenantId(dto.tenantId()); row.setDepartmentId(dto.departmentId()); row.setEnabled(dto.enabled());
        row.setDataUser(TicketScopeSql.identity(dto.dataUser()));
        if (dto.id() == null || (dto.password() != null && !dto.password().isEmpty())) row.setPasswordHash(LocalPassword.hash(dto.password()));
        row.setTokenSecret(LocalPassword.secret());
        if (dto.id() == null) mapper.insertUser(row); else mapper.updateUser(row);
    }
    public List<Dashboard> dashboards() { requireAdmin(); return mapper.dashboards(); }
    public Grant grants(long tenantId) { requireAdmin(); return new Grant(tenantId, mapper.grants(tenantId)); }
    @Transactional public void saveGrants(Grant grant) {
        requireAdmin();
        if (grant.tenantId() == null || mapper.tenant(grant.tenantId()) == null || grant.resourceIds() == null)
            throw new IllegalArgumentException("租户和看板列表不能为空");
        Set<Long> valid = new HashSet<>(); mapper.dashboards().forEach(d -> valid.add(d.id()));
        if (!valid.containsAll(grant.resourceIds())) throw new IllegalArgumentException("看板不存在");
        mapper.clearGrants(grant.tenantId());
        new HashSet<>(grant.resourceIds()).forEach(id -> mapper.insertGrant(grant.tenantId(), id));
    }
    public void requireDashboard(Long resourceId) {
        var user = LocalAccessContext.require(); user.requireTenant();
        if (!user.permits("dashboard") || resourceId == null || mapper.hasGrant(user.tenantId(), resourceId) != 1)
            throw new SecurityException("当前租户无权访问该看板");
    }
    public List<DatasetResource> datasets() { requireAdmin(); return mapper.datasets(); }
    public List<DatasourceResource> datasources() { requireAdmin(); return mapper.datasources(); }
    public DatasourceGrant datasourceGrants(long tenantId) {
        requireAdmin();
        if (mapper.tenant(tenantId) == null) throw new IllegalArgumentException("租户不存在");
        return new DatasourceGrant(tenantId, mapper.datasourceGrants(tenantId));
    }
    @Transactional public void saveDatasourceGrants(DatasourceGrant grant) {
        requireAdmin();
        if (grant.tenantId() == null || mapper.tenant(grant.tenantId()) == null || grant.datasourceIds() == null)
            throw new IllegalArgumentException("租户和数据源列表不能为空");
        Set<Long> valid = new HashSet<>(); mapper.datasources().forEach(source -> valid.add(source.id()));
        if (!valid.containsAll(grant.datasourceIds())) throw new IllegalArgumentException("数据源不存在");
        mapper.clearDatasourceGrants(grant.tenantId());
        new HashSet<>(grant.datasourceIds()).forEach(id -> mapper.insertDatasourceGrant(grant.tenantId(), id));
    }
    public void requireDatasource(Long datasourceId) {
        var user = LocalAccessContext.require();
        if (user.admin()) return;
        user.requireTenant();
        if (datasourceId == null || datasourceId <= 0) throw new SecurityException("数据源不存在或无访问权限");
        var source = mapper.datasource(datasourceId);
        if (source == null || "folder".equals(source.type())) throw new SecurityException("数据源不存在或无访问权限");
        var selected = mapper.datasourceGrants(user.tenantId());
        if (!selected.isEmpty() && !selected.contains(datasourceId)) throw new SecurityException("数据源不存在或无访问权限");
    }
    public void requireDatasetSources(io.dataease.api.dataset.union.DatasetGroupInfoDTO dataset) {
        requireUnionSources(dataset.getUnion());
    }
    private void requireUnionSources(List<io.dataease.api.dataset.union.UnionDTO> nodes) {
        if (nodes == null) return;
        for (var node : nodes) {
            if (node.getCurrentDs() != null) requireDatasource(node.getCurrentDs().getDatasourceId());
            requireUnionSources(node.getChildrenDs());
        }
    }
    public DatasetGrant datasetGrants(long tenantId) {
        requireAdmin();
        if (mapper.tenant(tenantId) == null) throw new IllegalArgumentException("租户不存在");
        return new DatasetGrant(tenantId, mapper.datasetGrants(tenantId));
    }
    @Transactional public void saveDatasetGrants(DatasetGrant grant) {
        requireAdmin();
        if (grant.tenantId() == null || mapper.tenant(grant.tenantId()) == null || grant.datasetIds() == null)
            throw new IllegalArgumentException("租户和数据集列表不能为空");
        Set<Long> valid = new HashSet<>(); mapper.datasets().forEach(dataset -> valid.add(dataset.id()));
        if (!valid.containsAll(grant.datasetIds())) throw new IllegalArgumentException("数据集不存在");
        mapper.clearDatasetGrants(grant.tenantId());
        new HashSet<>(grant.datasetIds()).forEach(id -> mapper.insertDatasetGrant(grant.tenantId(), id));
    }
    public LocalAccessMapper.DatasetRow requireDataset(Long datasetId) {
        var user = LocalAccessContext.require();
        if (datasetId == null) throw new SecurityException("数据集不存在或无访问权限");
        var dataset = mapper.dataset(datasetId);
        if (dataset == null) throw new SecurityException("数据集不存在或无访问权限");
        if (!user.admin()) {
            user.requireTenant();
            List<Long> selected = mapper.datasetGrants(user.tenantId());
            if (!visibleDataset(dataset, selected, user.userId())) throw new SecurityException("数据集不存在或无访问权限");
            if (!mapper.datasourceGrants(user.tenantId()).isEmpty())
                mapper.datasetDatasourceIds(datasetId).forEach(this::requireDatasource);
        }
        return dataset;
    }
    private boolean visibleDataset(LocalAccessMapper.DatasetRow dataset, List<Long> selected, long userId) {
        return "folder".equals(dataset.nodeType()) || selected.isEmpty() || selected.contains(dataset.id())
                || Long.toString(userId).equals(dataset.createBy());
    }
    public void requireDatasetDeletion(Long datasetId) {
        var identity = LocalAccessContext.require();
        if (!identity.permits("dataset") || !identity.permits("dataset_delete")) throw new SecurityException("当前角色无权删除数据集");
        requireDataset(datasetId);
        var user = LocalAccessContext.require();
        if (user.admin()) return;
        List<Long> selected = mapper.datasetGrants(user.tenantId());
        if (selected.isEmpty()) return;
        var nodes = mapper.datasetNodes();
        Set<Long> descendants = new HashSet<>(Set.of(datasetId));
        boolean changed;
        do {
            changed = false;
            for (var node : nodes) {
                if (descendants.contains(node.pid()) && descendants.add(node.id())) changed = true;
            }
        } while (changed);
        for (var node : nodes) {
            if (descendants.contains(node.id()) && !visibleDataset(node, selected, user.userId()))
                throw new SecurityException("文件夹包含无访问权限的数据集，不能删除");
        }
    }
    public void requireDatasetField(Long fieldId) {
        if (fieldId != null) {
            Long datasetId = mapper.fieldDataset(fieldId);
            if (datasetId != null) requireDataset(datasetId);
        }
    }
    public void requireChart(Long chartId, Long datasetId) {
        if (chartId == null) throw new SecurityException("图表不存在");
        requireDashboard(mapper.chartResource(chartId));
        if (datasetId != null && !datasetId.equals(mapper.chartDataset(chartId))) throw new SecurityException("图表数据集不匹配");
        if (datasetId != null) requireDataset(datasetId);
    }
    public void requireDashboardOperation(Long resourceId, String permission) {
        var user = LocalAccessContext.require();
        if (!user.permits("dashboard") || !user.permits(permission)) throw new SecurityException("当前角色无权执行此仪表板操作");
        requireDashboard(resourceId);
    }
    public void requireEditableChart(io.dataease.extensions.view.dto.ChartViewDTO view) {
        requireDashboardOperation(view.getSceneId(), "dashboard_edit");
        if (view.getId() != null) {
            for (Long owner : mapper.chartResources(view.getId())) {
                if (!owner.equals(view.getSceneId())) throw new SecurityException("图表不属于当前仪表板");
            }
        }
        if (view.getTableId() != null) requireDataset(view.getTableId());
        if (Boolean.TRUE.equals(view.getIsPlugin())) throw new SecurityException("本地租户模式暂不支持插件图表");
    }
    public void requireDashboardEditor() {
        var user = LocalAccessContext.require(); user.requireTenant();
        if (!user.permits("dashboard") || !user.permits("dashboard_edit")) throw new SecurityException("当前角色无权编辑或新建仪表板");
    }
    public void requireDashboardCreation(io.dataease.api.visualization.request.DataVisualizationBaseRequest request) {
        requireDashboardEditor();
        if (request.getId() != null || "copy".equals(request.getOptType()) || request.getAppData() != null
                || request.getNodeType() != null && !"leaf".equals(request.getNodeType())
                || request.getType() == null || !Set.of("dashboard", "dataV").contains(request.getType()))
            throw new SecurityException("当前角色仅可新建仪表板或大屏");
        if (request.getPid() != null && request.getPid() != 0L && !"folder".equals(mapper.visualizationNodeType(request.getPid())))
            throw new SecurityException("目标文件夹不存在");
        if (request.getCanvasViewInfo() != null) {
            for (var view : request.getCanvasViewInfo().values()) requireDraftChart(view);
        }
    }
    public void requireDraftChart(io.dataease.extensions.view.dto.ChartViewDTO view) {
        requireDashboardEditor();
        if (view.getId() != null && !mapper.chartResources(view.getId()).isEmpty()) throw new SecurityException("不能使用其他仪表板的图表 ID");
        if (view.getTableId() != null) requireDataset(view.getTableId());
        if (Boolean.TRUE.equals(view.getIsPlugin())) throw new SecurityException("本地租户模式暂不支持插件图表");
    }
    public void requireChartPreview(io.dataease.extensions.view.dto.ChartViewDTO view) {
        if (view.getSceneId() == null || view.getSceneId() <= 0) {
            var owners = view.getId() == null ? List.<Long>of() : mapper.chartResources(view.getId());
            if (owners.isEmpty()) requireDraftChart(view);
            else {
                for (Long owner : owners) requireDashboardOperation(owner, "dashboard_edit");
                if (view.getTableId() != null) requireDataset(view.getTableId());
                if (Boolean.TRUE.equals(view.getIsPlugin())) throw new SecurityException("本地租户模式暂不支持插件图表");
            }
        }
        else requireEditableChart(view);
    }
    public void requireChartEdit(Long chartId) {
        requireDashboardEditor();
        if (chartId == null) throw new SecurityException("图表不存在");
        var resources = mapper.chartResources(chartId);
        if (resources.isEmpty()) throw new SecurityException("请先保存仪表板后再修改计算字段");
        for (Long resourceId : resources) requireDashboardOperation(resourceId, "dashboard_edit");
    }
    public void requireChartFieldEdit(Long fieldId) {
        Long chartId = fieldId == null ? null : mapper.fieldChart(fieldId);
        if (chartId == null || chartId <= 0) throw new SecurityException("不能修改数据集原始字段");
        requireChartEdit(chartId);requireDatasetField(fieldId);
    }
    public void requireExistingChartFieldEdit(Long fieldId) {
        if (fieldId != null && mapper.fieldDataset(fieldId) != null) requireChartFieldEdit(fieldId);
    }
    public void createdDashboard(long resourceId, String nodeType) {
        if (LocalAccessContext.get() == null || !"leaf".equals(nodeType)) return;
        requireDashboardEditor(); var user = LocalAccessContext.require();
        mapper.insertGrant(user.tenantId(), resourceId);
    }
}
