package io.dataease.share.manage;

import io.dataease.api.xpack.share.request.XpackShareProxyRequest;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EmbedScopeVerifier {
    private final EmbedProperties properties;
    private final JdbcTemplate jdbc;
    @jakarta.annotation.Resource
    private io.dataease.system.manage.EmbedKeyManage embedKeyManage;
    public EmbedScopeVerifier(EmbedProperties properties, DataSource dataSource) {
        this.properties = properties;
        this.jdbc = new JdbcTemplate(dataSource);
    }
    public boolean required(XpackShareProxyRequest r) {
        return properties.getRequiredShares().contains(r.getUuid()) || r.getSig() != null || r.getKid() != null
            || r.getUserId() != null || r.getTenantId() != null || r.getIat() != null || r.getExp() != null || r.getNonce() != null;
    }
    public void verify(XpackShareProxyRequest r, String ticketTenant) {
        if (r.getIat() == null || r.getExp() == null || r.getKid() == null || r.getNonce() == null
            || !r.getKid().matches("[A-Za-z0-9_-]{1,64}") || !r.getNonce().matches("[A-Za-z0-9_-]{16,128}")
            || r.getTicket() == null || r.getUuid() == null) throw new IllegalArgumentException("Signed context required");
        String tenant = TicketScopeSql.identity(r.getTenantId());
        TicketScopeSql.identity(r.getUserId());
        EmbedProperties.Key key = embedKeyManage.resolve(r.getKid());
        if (key == null || !tenant.equals(key.getTenant()) || !tenant.equals(ticketTenant))
            throw new IllegalArgumentException("Invalid embed tenant");
        long now = Instant.now().getEpochSecond();
        EmbedSignature.verify(EmbedSignature.canonical(r.getKid(), r.getUuid(), r.getTicket(), tenant, r.getUserId(),
            r.getIat(), r.getExp(), r.getNonce()), key.getSecretBase64(), r.getSig(), now, r.getIat(), r.getExp());
        // Shared database UNIQUE key makes replay protection atomic across all instances.
        // Never log the signature or signed URL. Fail closed if the replay store is unavailable.
        jdbc.update("DELETE FROM de_embed_nonce WHERE expires_at < ? LIMIT 100", now);
        jdbc.update("INSERT INTO de_embed_nonce (key_id, nonce, expires_at) VALUES (?, ?, ?)",
            r.getKid(), r.getNonce(), r.getExp());
    }
}
