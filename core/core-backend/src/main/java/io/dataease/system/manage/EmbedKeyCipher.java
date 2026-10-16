package io.dataease.system.manage;

import io.dataease.rsa.manage.RsaManage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Encrypt using the existing installation AES key; bind ciphertext to tenant and key id. */
@Component
public class EmbedKeyCipher {
    private final RsaManage rsaManage;
    public EmbedKeyCipher(RsaManage rsaManage) {
        this.rsaManage = rsaManage;
    }
    private Cipher cipher(int mode, byte[] iv, String kid, String tenant) throws Exception {
        var model = rsaManage.query();
        if (model == null || model.getAesKey() == null || model.getAesKey().isBlank()) {
            throw new IllegalStateException("Installation encryption key unavailable");
        }
        byte[] key = MessageDigest.getInstance("SHA-256").digest(model.getAesKey().getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("embed-key-v1\n" + kid + "\n" + tenant).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    public String encrypt(String secret, String kid, String tenant) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, iv, kid, tenant).doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt embed key");
        }
    }
    public String decrypt(String encrypted, String kid, String tenant) {
        try {
            byte[] value = Base64.getDecoder().decode(encrypted);
            if (value.length < 29) throw new IllegalArgumentException();
            byte[] iv = Arrays.copyOf(value, 12);
            return new String(cipher(Cipher.DECRYPT_MODE, iv, kid, tenant)
                    .doFinal(Arrays.copyOfRange(value, 12, value.length)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt embed key");
        }
    }
}
