package io.dataease.share.manage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Canonical DE-EMBED-V1 HMAC. All fields (including the share and ticket) are bound. */
public final class EmbedSignature {
    private EmbedSignature() { }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
    public static String canonical(String kid, String uuid, String ticket, String tenant, String user,
                                   long iat, long exp, String nonce) {
        return String.join("\n", "DE-EMBED-V1", encode(kid), encode(uuid), encode(ticket), encode(tenant),
            encode(user), Long.toString(iat), Long.toString(exp), encode(nonce));
    }
    public static void verify(String canonical, String secretBase64, String signature, long now, long iat, long exp) {
        if (iat < 0 || iat > now + 30 || iat < now - 330 || exp <= now || exp <= iat || exp - iat > 300)
            throw new IllegalArgumentException("Expired embed signature");
        try {
            byte[] key = Base64.getDecoder().decode(secretBase64);
            if (key.length < 32) throw new IllegalArgumentException("Invalid embed key");
            if (signature == null || !signature.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid signature");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlDecoder().decode(signature))) throw new IllegalArgumentException("Invalid embed signature");
        } catch (GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable", e); }
    }
}
