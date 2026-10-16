package io.dataease.api.system.vo;

import lombok.Data;
import java.util.List;

@Data
public class IframeWhitelistVO {
    private List<String> origins;
    private boolean managed;
    private boolean enabled;
    private String effectivePolicy;
}
