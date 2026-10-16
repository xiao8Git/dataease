package io.dataease.system.manage;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Exact HTTP(S) origins only: never accept arbitrary CSP syntax from the settings API. */
public final class IframeOrigins {
    private IframeOrigins() {
    }

    public static List<String> normalize(List<String> origins) {
        if (origins == null || origins.size() > 50) {
            throw new IllegalArgumentException("Invalid iframe origins (maximum 50)");
        }
        var result = new LinkedHashSet<String>();
        for (String value : origins) {
            if (value == null || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid iframe origin");
            }
            String origin = value.trim();
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid iframe origin");
            }
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getRawPath();
            int port = uri.getPort();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(path == null || path.isEmpty() || path.equals("/"))
                    || port < -1 || port == 0 || port > 65535 || origin.contains("*")
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("Expected an HTTP(S) origin without path, credentials, query or wildcard");
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443);
            result.add(scheme + "://" + host + (defaultPort ? "" : ":" + port));
        }
        String policy = String.join(" ", result);
        if (policy.length() > 4096) {
            throw new IllegalArgumentException("Iframe whitelist is too long (maximum 4096 characters)");
        }
        return List.copyOf(result);
    }

    public static String policy(List<String> origins) {
        var normalized = normalize(origins);
        return "'self'" + (normalized.isEmpty() ? "" : " " + String.join(" ", normalized));
    }
}
