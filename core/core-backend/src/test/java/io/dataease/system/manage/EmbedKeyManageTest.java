package io.dataease.system.manage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.system.request.EmbedKeyEditor;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.i18n.Translator;
import io.dataease.rsa.dao.entity.CoreRsa;
import io.dataease.rsa.manage.RsaManage;
import io.dataease.share.manage.EmbedProperties;
import io.dataease.share.manage.EmbedScopeVerifier;
import io.dataease.share.manage.EmbedSignature;
import io.dataease.api.xpack.share.request.XpackShareProxyRequest;
import io.dataease.system.dao.ext.mapper.EmbedKeyMapper;
import io.dataease.utils.AuthUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class EmbedKeyManageTest {
    private EmbedKeyManage manage;
    private EmbedProperties properties;
    private EmbedKeyCipher cipher;
    private EmbedKeyMapper mapper;
    private SqlSession session;
    private DriverManagerDataSource ds;
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    @Before public void setup() throws Exception {
        new Translator().setMessageSource(new StaticMessageSource());
        var user = new TokenUserBO(); user.setUserId(1L); AuthUtils.setUser(user);
        ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(ds).execute(Files.readString(Path.of("src/main/resources/db/desktop/V2.10.27.3__embed_key.sql")));
        new JdbcTemplate(ds).execute(Files.readString(Path.of("src/main/resources/db/desktop/V2.10.27.1__embed_nonce.sql")).split(";")[0]);
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(EmbedKeyMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(EmbedKeyMapper.class);
        var rsa = mock(RsaManage.class); var model = new CoreRsa(); model.setAesKey("test-installation-aes-key");
        when(rsa.query()).thenReturn(model); cipher = new EmbedKeyCipher(rsa);
        properties = new EmbedProperties();
        var key = new EmbedProperties.Key(); key.setTenant("tenant-a"); key.setSecretBase64(SECRET);
        properties.setKeys(Map.of("legacy", key));
        manage = new EmbedKeyManage(mapper, properties, cipher);
    }
    @After public void cleanup() { AuthUtils.remove(); if (session != null) session.close(); }
    private EmbedKeyEditor editor(String kid, String tenant, String secret, boolean enabled) {
        var editor = new EmbedKeyEditor(); editor.setKid(kid); editor.setTenant(tenant);
        editor.setSecretBase64(secret); editor.setEnabled(enabled); return editor;
    }
    @Test public void encryptsAndPersistsIndependentTenantKeysWithoutReturningSecrets() throws Exception {
        String secretB = Base64.getEncoder().encodeToString("different-key-for-second-tenant-32".getBytes());
        manage.save(editor("a", "tenant-a", SECRET, true));
        manage.save(editor("b", "tenant-b", secretB, true));
        assertEquals(SECRET, manage.resolve("a").getSecretBase64());
        assertEquals(secretB, manage.resolve("b").getSecretBase64());
        assertNotEquals(SECRET, mapper.find("a").getCiphertext());
        String json = new ObjectMapper().writeValueAsString(manage.list());
        assertFalse(json.contains(SECRET)); assertFalse(json.contains("secretBase64")); assertFalse(json.contains("ciphertext"));
        assertFalse(new ObjectMapper().writeValueAsString(editor("a", "tenant-a", SECRET, true)).contains(SECRET));
    }
    @Test public void masksManagedAndDeploymentKeysAndRejectsMaskedInput() {
        String expected = SECRET.substring(0, 4) + "*".repeat(SECRET.length() - 8) + SECRET.substring(SECRET.length() - 4);
        assertEquals(expected, manage.list().get(0).getMaskedSecret());
        manage.save(editor("legacy", "tenant-a", "", false));
        assertEquals(expected, manage.list().get(0).getMaskedSecret());
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("legacy", "tenant-a", expected, true)));
        manage.save(editor("legacy", "tenant-a", "", true));
        assertEquals(SECRET, manage.resolve("legacy").getSecretBase64());
    }
    @Test public void blankPreservesSecretAndDisableOverridesYamlOnAllNodes() {
        assertEquals(SECRET, manage.resolve("legacy").getSecretBase64());
        manage.save(editor("legacy", "tenant-a", "", false));
        assertNull(manage.resolve("legacy"));
        var otherNode = new EmbedKeyManage(mapper, properties, cipher);
        assertNull(otherNode.resolve("legacy"));
        manage.save(editor("legacy", "tenant-a", "", true));
        assertEquals(SECRET, otherNode.resolve("legacy").getSecretBase64());
        assertTrue(manage.list().get(0).isManaged());
    }
    @Test public void preventsReassigningKidAcrossTenantsAndRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("legacy", "tenant-b", SECRET, true)));
        manage.save(editor("a", "tenant-a", SECRET, true));
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("a", "tenant-b", SECRET, true)));
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("a'", "tenant-a", SECRET, true)));
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("b", "tenant a", SECRET, true)));
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("b", "tenant-a", "", true)));
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("b", "tenant-a", "short", true)));
        assertThrows(IllegalArgumentException.class, () -> manage.save(editor("b", "tenant-a", Base64.getEncoder().encodeToString(new byte[129]), true)));
    }
    @Test public void adminIsRequiredForReadAndWrite() {
        var user = new TokenUserBO(); user.setUserId(2L); AuthUtils.setUser(user);
        assertThrows(Exception.class, () -> manage.list());
        assertThrows(Exception.class, () -> manage.save(editor("a", "tenant-a", SECRET, true)));
        assertNull(mapper.find("a"));
    }
    @Test public void encryptionIsRandomAndRejectsTamperingAndCrossTenantCiphertext() {
        String encrypted = cipher.encrypt(SECRET, "a", "tenant-a");
        assertNotEquals(encrypted, cipher.encrypt(SECRET, "a", "tenant-a"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, "a", "tenant-b"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, "b", "tenant-a"));
        byte[] bytes = Base64.getDecoder().decode(encrypted); bytes[20] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "a", "tenant-a"));
    }
    @Test public void storageFailureCannotFallBackToYaml() {
        var broken = mock(EmbedKeyMapper.class);
        when(broken.find("legacy")).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> new EmbedKeyManage(broken, properties, cipher).resolve("legacy"));
    }
    @Test public void managedKeyVerifiesSignedRequestsAndRejectsWrongTenantDisabledAndReplay() throws Exception {
        manage.save(editor("a", "tenant-a", SECRET, true));
        var verifier = new EmbedScopeVerifier(properties, ds);
        ReflectionTestUtils.setField(verifier, "embedKeyManage", manage);
        var r = new XpackShareProxyRequest(); r.setKid("a"); r.setTenantId("tenant-a"); r.setUserId("user-a");
        r.setUuid("share"); r.setTicket("ticket"); r.setNonce("nonce-0123456789abcdef");
        r.setIat(Instant.now().getEpochSecond()); r.setExp(r.getIat()+300);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256"));
        r.setSig(Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(EmbedSignature.canonical(r.getKid(),r.getUuid(),r.getTicket(),r.getTenantId(),r.getUserId(),r.getIat(),r.getExp(),r.getNonce()).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(r, "tenant-b"));
        verifier.verify(r, "tenant-a");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> verifier.verify(r, "tenant-a"));
        manage.save(editor("a", "tenant-a", "", false));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(r, "tenant-a"));
    }
}
