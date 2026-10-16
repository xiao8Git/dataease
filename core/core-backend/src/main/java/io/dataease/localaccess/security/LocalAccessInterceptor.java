package io.dataease.localaccess.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.permissions.login.dto.PwdLoginDTO;
import io.dataease.constant.AuthConstant;
import io.dataease.localaccess.manage.LocalAccessManage;
import io.dataease.result.ResultMessage;
import io.dataease.utils.RsaUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LocalAccessInterceptor implements HandlerInterceptor {
    private final LocalAccessManage manage;
    private final ObjectMapper json;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private record Attempt(int count, long until) { }
    public LocalAccessInterceptor(LocalAccessManage manage, ObjectMapper json) { this.manage = manage; this.json = json; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!manage.enabled() || !(handler instanceof HandlerMethod)) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/de2api/")) path = path.substring(7);
        try {
            if (path.equals("/login/localLogin")) {
                if (!"POST".equals(request.getMethod())) throw new SecurityException("无效登录请求");
                String key = request.getRemoteAddr();
                long now = System.currentTimeMillis();
                attempts.entrySet().removeIf(e -> e.getValue().until() < now);
                Attempt old = attempts.get(key);
                if (old != null && old.count() >= 10 || attempts.size() > 10000) throw new SecurityException("登录尝试过多，请稍后重试");
                attempts.compute(key, (k, previous) -> new Attempt(previous == null ? 1 : previous.count() + 1, now + 600000));
                PwdLoginDTO dto = json.readValue(request.getInputStream(), PwdLoginDTO.class);
                var result = manage.login(RsaUtils.decryptStr(dto.getName()), RsaUtils.decryptStr(dto.getPwd()));
                attempts.remove(key);
                return reply(response, ResultMessage.success(Map.of("token", result.getToken(), "exp", result.getExp(),
                        "localAccess", true)), 200);
            }
            if (LocalAccessPolicy.PUBLIC.contains(path)) return true;
            // The download controller verifies a short-lived, single-use task ticket.
            if ("GET".equals(request.getMethod()) && path.matches("/exportCenter/download/[0-9]+")) return true;
            if ("GET".equals(request.getMethod()) && (path.startsWith("/xpackComponent/content/")
                    || path.startsWith("/xpackComponent/pluginStaticInfo/") || path.startsWith("/i18n/custom_"))) return true;
            // Existing signed share links retain their resource-specific verification chain.
            if (request.getHeader(AuthConstant.LINK_TOKEN_KEY) != null && LocalAccessContext.get() == null) return true;
            var user = LocalAccessContext.require();
            if (path.startsWith("/customGeo/") && !"GET".equals(request.getMethod()) && !user.admin())
                throw new SecurityException("地图配置仅允许管理员修改");
            if (!LocalAccessPolicy.allowed(path, user)) throw new SecurityException("当前角色无权使用此功能，请联系管理员");
            if (path.equals("/user/info") || path.equals("/user/personInfo") || path.equals("/user/ipInfo")) {
                return reply(response, ResultMessage.success(Map.of("id", Long.toString(user.userId()), "account", user.account(),
                        "name", user.name(), "oid", user.tenantId() == null ? "0" : user.tenantId().toString(),
                        "language", manage.language(), "model", "lose", "localAccess", true)), 200);
            }
            if (path.equals("/org/mounted")) return reply(response, ResultMessage.success(manage.session().tenants()), 200);
            if (path.equals("/login/refresh")) return reply(response, ResultMessage.success(manage.refresh()), 200);
            if (path.equals("/logout")) { manage.logout(); return reply(response, ResultMessage.success(), 200); }
            if (path.equals("/user/switchLanguage")) {
                manage.language(json.readTree(request.getInputStream()).path("lang").asText());
                return reply(response, ResultMessage.success(), 200);
            }
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return reply(response, new ResultMessage(403, e.getMessage()), 403);
        }
    }
    private boolean reply(HttpServletResponse response, Object value, int status) throws Exception {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), value); return false;
    }
}
