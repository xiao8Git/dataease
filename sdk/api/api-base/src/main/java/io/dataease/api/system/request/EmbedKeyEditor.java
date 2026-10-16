package io.dataease.api.system.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmbedKeyEditor {
    private String kid;
    private String tenant;
    private boolean enabled;
    // Blank keeps the existing secret. Never serialize or include secrets in toString.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String secretBase64;
}
