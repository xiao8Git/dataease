package io.dataease.share.manage;

import java.util.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dataease.embed")
public class EmbedProperties {
    // Configuring a share here also disables its legacy unsigned-ticket path.
    private Set<String> requiredShares = new HashSet<>();
    private Map<String, Key> keys = new HashMap<>();
    @Data
    public static class Key {
        private String tenant;
        private String secretBase64;
    }
}
