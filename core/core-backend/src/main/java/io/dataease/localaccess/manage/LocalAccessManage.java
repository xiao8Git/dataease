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
    public static final Set<String> FEATURES = Set.of("dashboard", "dataset");
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
    public void requireChart(Long chartId, Long datasetId) {
        if (chartId == null) throw new SecurityException("图表不存在");
        requireDashboard(mapper.chartResource(chartId));
        if (datasetId != null && !datasetId.equals(mapper.chartDataset(chartId))) throw new SecurityException("图表数据集不匹配");
    }
    public void createdDashboard(long resourceId, String nodeType) {
        if (LocalAccessContext.get() == null || !"leaf".equals(nodeType)) return;
        requireAdmin(); var user = LocalAccessContext.require(); user.requireTenant();
        mapper.insertGrant(user.tenantId(), resourceId);
    }
}
