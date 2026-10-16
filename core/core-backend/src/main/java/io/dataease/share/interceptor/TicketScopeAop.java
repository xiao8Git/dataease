package io.dataease.share.interceptor;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.xpack.share.vo.XpackShareProxyVO;
import io.dataease.api.xpack.share.request.XpackShareProxyRequest;
import io.dataease.share.manage.EmbedScopeVerifier;
import jakarta.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import io.dataease.constant.AuthConstant;
import io.dataease.share.manage.TicketScopeSql;
import io.dataease.utils.ServletUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Bind server-validated ticket arguments to the signed link token. */
@Aspect
@Component
public class TicketScopeAop {
    @Resource private EmbedScopeVerifier embedVerifier;
    private final ObjectMapper mapper = new ObjectMapper();

    @Around("execution(* io.dataease.api.xpack.share.XpackShareApi+.proxyInfo(..))")
    public Object bindScope(ProceedingJoinPoint point) throws Throwable {
        XpackShareProxyRequest request = (XpackShareProxyRequest) point.getArgs()[0];
        Object result = point.proceed();
        boolean protectedShare = embedVerifier.required(request);
        String original = ServletUtils.response().getHeader(AuthConstant.LINK_TOKEN_KEY);
        if (protectedShare) ServletUtils.response().setHeader(AuthConstant.LINK_TOKEN_KEY, "");
        if (!(result instanceof XpackShareProxyVO info) || info.isExp() || info.isShareDisable()
                || !info.isPwdValid() || !info.isPeRequireValid() || info.isInIframeError()
                || info.getTicketValidVO() == null || !info.getTicketValidVO().isTicketValid()
                || info.getTicketValidVO().isTicketExp()) return result;
        String ticketArgs = info.getTicketValidVO().getArgs();
        if (ticketArgs == null) {
            if (protectedShare) throw new IllegalArgumentException("Scoped ticket arguments required");
            return result;
        }
        JsonNode args = mapper.readTree(ticketArgs);
        if (args == null || !args.isObject()) throw new IllegalArgumentException("Invalid ticket arguments");
        boolean dynamic = protectedShare || args.has("user_id") || args.path("dynamic_user").asBoolean(false);
        if (!dynamic) return result;
        ServletUtils.response().setHeader(AuthConstant.LINK_TOKEN_KEY, "");
        String tenant = scalar(args.get("tenant_id"));
        // Original extension has already validated the ticket, password and iframe policy.
        embedVerifier.verify(request, tenant);
        String user = TicketScopeSql.identity(request.getUserId());
        var response = ServletUtils.response();
        var algorithm = TicketScopeSql.algorithm(info.getResourceId(), info.getUid());
        DecodedJWT jwt = JWT.require(algorithm).build().verify(original);
        var builder = JWT.create().withClaim("uid", jwt.getClaim("uid").asLong())
                .withClaim("oid", jwt.getClaim("oid").asLong())
                .withClaim("resourceId", info.getResourceId())
                .withClaim("scopeTenant", tenant).withClaim("scopeUser", user);
        {
            Instant expires = Instant.now().plusSeconds(900);
            if (jwt.getExpiresAt() != null && jwt.getExpiresAt().toInstant().isBefore(expires)) expires = jwt.getExpiresAt().toInstant();
            builder.withExpiresAt(Date.from(expires));
            // Return the verified identity to parameter consumers, never the old fixed ticket user.
            var effective = ((com.fasterxml.jackson.databind.node.ObjectNode) args).deepCopy();
            effective.putArray("user_id").add(user);
            info.getTicketValidVO().setArgs(mapper.writeValueAsString(effective));
        }
        response.setHeader(AuthConstant.LINK_TOKEN_KEY, builder.sign(algorithm));
        return result;
    }

    private String scalar(JsonNode node) {
        if (node != null && node.isArray() && node.size() == 1) node = node.get(0);
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("Ticket scope requires one tenant and one user");
        return TicketScopeSql.identity(node.asText());
    }
}
