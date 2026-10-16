package io.dataease.localaccess.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class LocalPassword {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600000;
    private LocalPassword() { }
    public static String secret() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
    public static String hash(String password) {
        if (password == null || password.length() < 12 || password.length() > 128)
            throw new IllegalArgumentException("密码长度必须为 12 至 128 个字符");
        String salt = secret();
        return ITERATIONS + "$" + salt + "$" + derive(password, salt, ITERATIONS);
    }
    public static boolean matches(String password, String hash) {
        if (password == null || password.length() > 128) return false;
        try {
            String[] parts = hash.split("\\$");
            int iterations = Integer.parseInt(parts[0]);
            if (iterations != ITERATIONS) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]),
                    Base64.getDecoder().decode(derive(password, parts[1], iterations)));
        } catch (RuntimeException e) { return false; }
    }
    private static String derive(String password, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), iterations, 256);
        try {
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) { throw new IllegalStateException("Password hashing unavailable", e); }
        finally { spec.clearPassword(); }
    }
}
