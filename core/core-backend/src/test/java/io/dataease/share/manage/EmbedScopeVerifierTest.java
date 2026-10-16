package io.dataease.share.manage;

import io.dataease.api.xpack.share.request.XpackShareProxyRequest;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbedScopeVerifierTest {
    private EmbedScopeVerifier verifier;
    private JdbcTemplate jdbc;
    private EmbedProperties properties;
    @BeforeEach void setup() {
        properties = new EmbedProperties(); properties.setRequiredShares(Set.of("protected"));
        var key = new EmbedProperties.Key(); key.setTenant("tenant-a");
        key.setSecretBase64(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setKeys(Map.of("test", key));
        verifier = new EmbedScopeVerifier(properties, mock(DataSource.class));
        var keys = mock(io.dataease.system.manage.EmbedKeyManage.class);
        when(keys.resolve(anyString())).thenAnswer(call -> properties.getKeys().get(call.getArgument(0)));
        ReflectionTestUtils.setField(verifier, "embedKeyManage", keys);
        jdbc = mock(JdbcTemplate.class); ReflectionTestUtils.setField(verifier, "jdbc", jdbc);
    }
    private XpackShareProxyRequest request() throws Exception {
        var r = new XpackShareProxyRequest(); r.setUuid("protected"); r.setTicket("ticket");
        r.setKid("test"); r.setTenantId("tenant-a"); r.setUserId("admin"); r.setNonce("random-nonce-at-least-16");
        r.setIat(Instant.now().getEpochSecond()); r.setExp(r.getIat() + 300);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256"));
        r.setSig(Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(EmbedSignature.canonical(
            r.getKid(), r.getUuid(), r.getTicket(), r.getTenantId(), r.getUserId(), r.getIat(), r.getExp(), r.getNonce()).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return r;
    }
    @Test void verifiesAndConsumesNonce() throws Exception {
        var r = request(); verifier.verify(r, "tenant-a");
        verify(jdbc).update("INSERT INTO de_embed_nonce (key_id, nonce, expires_at) VALUES (?, ?, ?)", "test", r.getNonce(), r.getExp());
    }
    @Test void tenantTicketUserTamperingNeverReachesReplayStore() throws Exception {
        var original = request(); assertThrows(IllegalArgumentException.class, () -> verifier.verify(original, "other"));
        original.setTenantId("other"); assertThrows(IllegalArgumentException.class, () -> verifier.verify(original, "other"));
        var r = request(); r.setUserId("another-user"); var user = r;
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(user, "tenant-a"));
        r = request(); r.setTicket("another-ticket"); var ticket = r;
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(ticket, "tenant-a"));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsMissingAndExpiredSignaturesAndReplayStoreFailure() throws Exception {
        var unsigned = new XpackShareProxyRequest(); unsigned.setUuid("protected");
        assertTrue(verifier.required(unsigned));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(unsigned, "tenant-a"));
        var expired = request(); expired.setExp(Instant.now().getEpochSecond() - 1);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(expired, "tenant-a"));
        var valid = request();
        when(jdbc.update("INSERT INTO de_embed_nonce (key_id, nonce, expires_at) VALUES (?, ?, ?)", "test", valid.getNonce(), valid.getExp()))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("replay"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> verifier.verify(valid, "tenant-a"));
    }
    @Test void matchesIndependentVectorAndRejectsTimestampAndPayloadChanges() {
        String canonical = EmbedSignature.canonical("test-key", "share-1", "ticket/+%", "tenant-a", "user/a@b",
            1700000000L, 1700000300L, "nonce-0123456789abcdef");
        String key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String sig = "jJIxxz8GhOeJB2MDF5xA04fjO_hlGHqfwolxJ1Cm1_s";
        assertDoesNotThrow(() -> EmbedSignature.verify(canonical, key, sig, 1700000001L, 1700000000L, 1700000300L));
        assertThrows(IllegalArgumentException.class, () -> EmbedSignature.verify(canonical + "x", key, sig, 1700000001L, 1700000000L, 1700000300L));
        assertThrows(IllegalArgumentException.class, () -> EmbedSignature.verify(canonical, key, sig, 1700000300L, 1700000000L, 1700000300L));
        assertThrows(IllegalArgumentException.class, () -> EmbedSignature.verify(canonical, key, sig, 1699999900L, 1700000000L, 1700000300L));
    }

}
