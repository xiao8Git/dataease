package io.dataease.utils;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.auth.config.SubstituleLoginConfig;
import io.dataease.exception.DEException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;

public class TokenUtils {

    public static TokenUserBO userBOByToken(String token) {
        DecodedJWT jwt = JWT.decode(token);
        Long userId = jwt.getClaim("uid").asLong();
        Long oid = jwt.getClaim("oid").asLong();
        if (ObjectUtils.isEmpty(userId)) {
            DEException.throwException("token格式错误！");
        }
        return new TokenUserBO(userId, oid);
    }

    public static TokenUserBO validate(String token) {
        var local = CommonBeanFactory.getBean(io.dataease.auth.LocalIdentityProvider.class);
        if (local != null && local.enabled()) return local.validate(token);
        if (StringUtils.isBlank(token)) {
            String uri = ServletUtils.request().getRequestURI();
            DEException.throwException("token is empty for uri {" + uri + "}");
        }
        if (StringUtils.length(token) < 100) {
            DEException.throwException("token is invalid");
        }
        Object apisixTokenManage = CommonBeanFactory.getBean("apisixTokenManage");
        if (ObjectUtils.isNotEmpty(apisixTokenManage)
                && ReflectionUtils.findMethod(apisixTokenManage.getClass(), "validate", String.class) != null) {
            return validateByApisixTokenManage(apisixTokenManage, token);
        }
        return validateFallbackToken(token);
    }


    public static TokenUserBO validateLinkToken(String linkToken) {
        if (StringUtils.isBlank(linkToken)) {
            String uri = ServletUtils.request().getRequestURI();
            DEException.throwException("link token is empty for uri {" + uri + "}");
        }
        if (StringUtils.length(linkToken) < 100) {
            DEException.throwException("token is invalid");
        }
        DecodedJWT jwt = JWT.decode(linkToken);
        Long userId = jwt.getClaim("uid").asLong();
        Long oid = jwt.getClaim("oid").asLong();
        if (ObjectUtils.isEmpty(userId)) {
            DEException.throwException("link token格式错误！");
        }
        return new TokenUserBO(userId, oid);
    }

    private static TokenUserBO validateByApisixTokenManage(Object apisixTokenManage, String token) {
        java.lang.reflect.Method validateMethod = ReflectionUtils.findMethod(apisixTokenManage.getClass(), "validate", String.class);
        if (validateMethod == null) {
            return validateFallbackToken(token);
        }
        Object tokenBO = ReflectionUtils.invokeMethod(validateMethod, apisixTokenManage, token);
        if (ObjectUtils.isEmpty(tokenBO)) {
            DEException.throwException("token is invalid");
        }
        Long userId = (Long) ReflectionUtils.invokeMethod(DeReflectUtil.findMethod(tokenBO.getClass(), "getUserId"), tokenBO);
        Long defaultOid = (Long) ReflectionUtils.invokeMethod(DeReflectUtil.findMethod(tokenBO.getClass(), "getDefaultOid"), tokenBO);
        return new TokenUserBO(userId, defaultOid);
    }

    private static TokenUserBO validateFallbackToken(String token) {
        TokenUserBO userBO = userBOByToken(token);
        String secret;
        if (CommonBeanFactory.getBean("loginServer") == null) {
            secret = SubstituleLoginConfig.getTokenSecret();
        } else {
            // Older permission extensions expose the user cache but not validate(String).
            Object cacheManage = CommonBeanFactory.getBean("apisixCacheManage");
            if (cacheManage == null) {
                throw new IllegalStateException("token validation service is unavailable");
            }
            Method cacheMethod = ReflectionUtils.findMethod(cacheManage.getClass(), "userCacheBO", Long.class);
            if (cacheMethod == null) {
                throw new IllegalStateException("token validation service is incompatible");
            }
            Object cache = ReflectionUtils.invokeMethod(cacheMethod, cacheManage, userBO.getUserId());
            if (cache == null || !Boolean.TRUE.equals(readCacheProperty(cache, "getEnable"))
                    || !userBO.getUserId().equals(readCacheProperty(cache, "getId"))) {
                throw new IllegalArgumentException("token user is invalid");
            }
            Object validOids = readCacheProperty(cache, "getValidOidList");
            if (!(validOids instanceof List<?> oidList) || !oidList.contains(userBO.getDefaultOid())) {
                throw new IllegalArgumentException("token organization is invalid");
            }
            secret = (String) readCacheProperty(cache, "getSecret");
        }
        if (StringUtils.isBlank(secret)) {
            DEException.throwException("token is invalid");
        }
        Algorithm algorithm = Algorithm.HMAC256(secret);
        Verification verification = JWT.require(algorithm).withClaim("uid", userBO.getUserId()).withClaim("oid", userBO.getDefaultOid());
        JWTVerifier verifier = verification.build();
        verifier.verify(token);
        return userBO;
    }

    private static Object readCacheProperty(Object cache, String name) {
        Method method = ReflectionUtils.findMethod(cache.getClass(), name);
        if (method == null) {
            throw new IllegalStateException("token validation service is incompatible");
        }
        return ReflectionUtils.invokeMethod(method, cache);
    }
}
