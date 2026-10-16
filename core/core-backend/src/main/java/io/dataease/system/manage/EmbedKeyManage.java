package io.dataease.system.manage;

import io.dataease.api.system.request.EmbedKeyEditor;
import io.dataease.api.system.vo.EmbedKeyVO;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.share.manage.EmbedProperties;
import io.dataease.share.manage.TicketScopeSql;
import io.dataease.system.dao.ext.mapper.EmbedKeyMapper;
import io.dataease.utils.AuthUtils;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EmbedKeyManage {
    private final EmbedKeyMapper mapper;
    private final EmbedProperties properties;
    private final EmbedKeyCipher cipher;

    public EmbedKeyManage(EmbedKeyMapper mapper, EmbedProperties properties, EmbedKeyCipher cipher) {
        this.mapper = mapper;
        this.properties = properties;
        this.cipher = cipher;
    }
    private void checkAdmin() {
        if (!AuthUtils.isSysAdmin()) DEException.throwException(Translator.get("i18n_no_permission"));
    }
    private void validateKid(String kid) {
        if (kid == null || !kid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("kid must contain 1-64 letters, digits, underscores or hyphens");
        }
    }
    private String validateSecret(String value) {
        try {
            if (value == null || value.length() > 172) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 32 || bytes.length > 128) throw new IllegalArgumentException();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Secret must be Base64 encoding of 32-128 bytes");
        }
    }
    public List<EmbedKeyVO> list() {
        checkAdmin();
        Map<String, EmbedKeyVO> result = new TreeMap<>();
        properties.getKeys().forEach((kid, key) -> result.put(kid, view(kid, key.getTenant(), true, false, key.getSecretBase64())));
        mapper.list().forEach(row -> result.put(row.getKid(), view(row.getKid(), row.getTenant(), row.isEnabled(), true,
                cipher.decrypt(row.getCiphertext(), row.getKid(), row.getTenant()))));
        return new ArrayList<>(result.values());
    }
    private EmbedKeyVO view(String kid, String tenant, boolean enabled, boolean managed, String secret) {
        var vo = new EmbedKeyVO();
        vo.setKid(kid); vo.setTenant(tenant); vo.setEnabled(enabled); vo.setManaged(managed);
        vo.setMaskedSecret(maskSecret(secret));
        return vo;
    }
    private String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        if (secret.length() <= 8) return "*".repeat(secret.length());
        return secret.substring(0, 4) + "*".repeat(secret.length() - 8) + secret.substring(secret.length() - 4);
    }
    @Transactional
    public void save(EmbedKeyEditor editor) {
        checkAdmin();
        if (editor == null) throw new IllegalArgumentException("Key configuration required");
        validateKid(editor.getKid());
        String tenant = TicketScopeSql.identity(editor.getTenant());
        var existing = mapper.find(editor.getKid());
        var deployment = properties.getKeys().get(editor.getKid());
        String currentTenant = existing != null ? existing.getTenant() : deployment == null ? null : deployment.getTenant();
        if (currentTenant != null && !tenant.equals(currentTenant)) {
            throw new IllegalArgumentException("A kid cannot be reassigned to another tenant; use a new kid");
        }
        String secret = editor.getSecretBase64();
        var row = new EmbedKeyMapper.Entry();
        row.setKid(editor.getKid()); row.setTenant(tenant); row.setEnabled(editor.isEnabled());
        if (secret == null || secret.isBlank()) {
            if (existing != null) row.setCiphertext(existing.getCiphertext());
            else if (deployment != null) row.setCiphertext(cipher.encrypt(validateSecret(deployment.getSecretBase64()), row.getKid(), tenant));
            else throw new IllegalArgumentException("A new key requires a secret");
        } else {
            row.setCiphertext(cipher.encrypt(validateSecret(secret), row.getKid(), tenant));
        }
        if (existing == null) mapper.insert(row);
        else if (mapper.update(row) != 1) throw new IllegalStateException("Key configuration changed; reload and retry");
    }
    /** No cached secrets: changes apply to the next exchange on every node. Storage errors fail closed. */
    public EmbedProperties.Key resolve(String kid) {
        validateKid(kid);
        var row = mapper.find(kid);
        if (row == null) return properties.getKeys().get(kid);
        if (!row.isEnabled()) return null;
        var key = new EmbedProperties.Key();
        key.setTenant(row.getTenant());
        key.setSecretBase64(validateSecret(cipher.decrypt(row.getCiphertext(), kid, row.getTenant())));
        return key;
    }
}
