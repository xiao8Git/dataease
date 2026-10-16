package io.dataease.api.xpack.share.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "分享代理信息过滤器")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class XpackShareProxyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 7758730984988104057L;

    @Schema(description = "分享UUID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String uuid;

    @Schema(description = "密钥", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ciphertext;

    private boolean inIframe;

    private String ticket;

    @com.fasterxml.jackson.annotation.JsonProperty("tenant_id")
    private String tenantId;

    @com.fasterxml.jackson.annotation.JsonProperty("user_id")
    private String userId;

    private Long iat;
    private Long exp;
    private String nonce;
    private String kid;
    private String sig;

    // Preserve the existing Java constructor used by extension implementations.
    public XpackShareProxyRequest(String uuid, String ciphertext, boolean inIframe, String ticket) {
        this.uuid = uuid;
        this.ciphertext = ciphertext;
        this.inIframe = inIframe;
        this.ticket = ticket;
    }
}
