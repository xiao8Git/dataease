package io.dataease.system.manage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.api.system.vo.IframeWhitelistVO;
import io.dataease.exception.DEException;
import io.dataease.filter.FramePolicyProvider;
import io.dataease.i18n.Translator;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.dao.auto.mapper.CoreSysSettingMapper;
import io.dataease.utils.AuthUtils;
import io.dataease.utils.IDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class IframeWhitelistManage implements FramePolicyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(IframeWhitelistManage.class);
    private static final String KEY = "security.iframeOrigins";
    private static final String ENABLED_KEY = "security.iframeValidationEnabled";
    private final CoreSysSettingMapper mapper;
    private final String deploymentPolicy;
    private volatile CachedPolicy cache;

    public IframeWhitelistManage(CoreSysSettingMapper mapper,
            @Value("${dataease.http.frame-ancestors:'self'}") String deploymentPolicy) {
        this.mapper = mapper;
        this.deploymentPolicy = deploymentPolicy == null || deploymentPolicy.isBlank() ? "'self'" : deploymentPolicy;
    }

    private record CachedPolicy(String policy, long expiresAt) {
    }

    private CoreSysSetting read() {
        return mapper.selectOne(new QueryWrapper<CoreSysSetting>().eq("pkey", KEY));
    }

    private boolean enabled() {
        CoreSysSetting setting = mapper.selectOne(new QueryWrapper<CoreSysSetting>().eq("pkey", ENABLED_KEY));
        // Missing or invalid values retain validation for existing deployments.
        return setting == null || !"false".equals(setting.getPval());
    }

    private List<String> origins(CoreSysSetting setting) {
        String value = setting.getPval();
        return IframeOrigins.normalize(value == null || value.isBlank()
                ? List.of() : Arrays.asList(value.split("\n")));
    }

    private void checkAdmin() {
        if (!AuthUtils.isSysAdmin()) {
            DEException.throwException(Translator.get("i18n_no_permission"));
        }
    }

    public IframeWhitelistVO query() {
        checkAdmin();
        CoreSysSetting setting = read();
        var result = new IframeWhitelistVO();
        result.setManaged(setting != null);
        result.setEnabled(enabled());
        result.setOrigins(setting == null ? List.of() : origins(setting));
        result.setEffectivePolicy(!result.isEnabled() ? "*" : setting == null ? deploymentPolicy : IframeOrigins.policy(result.getOrigins()));
        return result;
    }

    @Transactional
    public void save(List<String> origins) {
        save(origins, null);
    }

    @Transactional
    public void save(List<String> origins, Boolean enabled) {
        checkAdmin();
        List<String> normalized = IframeOrigins.normalize(origins);
        CoreSysSetting setting = read();
        if (setting == null) {
            setting = new CoreSysSetting();
            setting.setId(IDUtils.snowID());
            setting.setPkey(KEY);
            setting.setPval(String.join("\n", normalized));
            setting.setType("text");
            setting.setSort(0);
            mapper.insert(setting);
        } else {
            setting.setPval(String.join("\n", normalized));
            mapper.updateById(setting);
        }
        if (enabled != null) {
            CoreSysSetting toggle = mapper.selectOne(new QueryWrapper<CoreSysSetting>().eq("pkey", ENABLED_KEY));
            if (toggle == null) {
                toggle = new CoreSysSetting();
                toggle.setId(IDUtils.snowID());
                toggle.setPkey(ENABLED_KEY);
                toggle.setPval(enabled.toString());
                toggle.setType("text");
                toggle.setSort(0);
                mapper.insert(toggle);
            } else {
                toggle.setPval(enabled.toString());
                mapper.updateById(toggle);
            }
        }
        // Refresh from committed storage within five seconds on every node, including this node.
    }

    @Override
    public synchronized String frameAncestors() {
        long now = System.nanoTime();
        if (cache != null && now < cache.expiresAt()) {
            return cache.policy();
        }
        String policy;
        try {
            CoreSysSetting setting = read();
            policy = !enabled() ? "*" : setting == null ? null : IframeOrigins.policy(origins(setting));
        } catch (Exception e) {
            // Never reopen previously revoked origins if storage is unavailable or corrupted.
            LOG.warn("Cannot load iframe whitelist; allowing same-origin embedding only", e);
            policy = "'self'";
        }
        cache = new CachedPolicy(policy, now + TimeUnit.SECONDS.toNanos(5));
        return policy;
    }
}
