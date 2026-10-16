package io.dataease.api.system.vo;

import lombok.Data;

@Data
public class EmbedKeyVO {
    private String kid;
    private String tenant;
    private String maskedSecret;
    private boolean enabled;
    private boolean managed;
}
