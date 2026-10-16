package io.dataease.share.interceptor;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.dataease.api.xpack.share.vo.*;
import io.dataease.api.xpack.share.request.XpackShareProxyRequest;
import io.dataease.share.manage.*;
import io.dataease.utils.CommonBeanFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.*;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketScopeAopTest {
    public static class Secret { public String getSecret(Long resource, Long user) { return "test-only-link-secret"; } }
    private TicketScopeAop aop;
    private EmbedScopeVerifier verifier;
    private MockHttpServletResponse response;
    private MockHttpServletRequest http;
    private XpackShareProxyRequest request;
    private XpackShareProxyVO info;
    private ProceedingJoinPoint point;
    private StaticApplicationContext context;
    @BeforeEach void setup() throws Throwable {
        context = new StaticApplicationContext(); context.getBeanFactory().registerSingleton("shareSecretManage", new Secret());
        new CommonBeanFactory().setApplicationContext(context);
        response = new MockHttpServletResponse(); http = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http, response));
        response.setHeader("X-DE-LINK-TOKEN", JWT.create().withClaim("uid",1L).withClaim("oid",1L)
            .withClaim("resourceId",2L).sign(Algorithm.HMAC256("test-only-link-secret")));
        aop = new TicketScopeAop(); verifier = mock(EmbedScopeVerifier.class);
        ReflectionTestUtils.setField(aop,"embedVerifier",verifier);
        request = new XpackShareProxyRequest(); request.setUuid("share"); request.setUserId("current-user");
        info = new XpackShareProxyVO(); info.setUid(1L); info.setResourceId(2L); info.setPwdValid(true); info.setInIframeError(false);
        var ticket = new TicketValidVO(); ticket.setTicketValid(true);
        ticket.setArgs("{\"tenant_id\":[\"tenant-a\"],\"user_id\":[\"old-fixed-admin\"]}"); info.setTicketValidVO(ticket);
        point = mock(ProceedingJoinPoint.class); when(point.getArgs()).thenReturn(new Object[]{request}); when(point.proceed()).thenReturn(info);
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); context.close(); }
    @Test void bindsVerifiedCurrentUserAndExpiresSessionAndSql() throws Throwable {
        aop.bindScope(point); verify(verifier).verify(request,"tenant-a");
        String token = response.getHeader("X-DE-LINK-TOKEN");
        var jwt = JWT.require(Algorithm.HMAC256("test-only-link-secret")).build().verify(token);
        assertEquals("current-user",jwt.getClaim("scopeUser").asString());
        assertEquals("tenant-a",jwt.getClaim("scopeTenant").asString());
        assertTrue(jwt.getExpiresAt().toInstant().isBefore(Instant.now().plusSeconds(901)));
        assertFalse(info.getTicketValidVO().getArgs().contains("old-fixed-admin"));
        http.addHeader("X-DE-LINK-TOKEN",token);
        assertEquals("SELECT 'tenant-a', 'current-user'",TicketScopeSql.bind("SELECT '__DE_TICKET_TENANT__', '__DE_TICKET_USER__'"));
    }
    @Test void fixedUserTicketsAlsoRequireSignatureAndDoNotLeakOriginalToken() throws Throwable {
        doThrow(new IllegalArgumentException("signature required")).when(verifier).verify(request,"tenant-a");
        assertThrows(IllegalArgumentException.class,()->aop.bindScope(point));
        assertEquals("",response.getHeader("X-DE-LINK-TOKEN"));
    }
    @Test void invalidProtectedTicketCannotIssueSession() throws Throwable {
        when(verifier.required(request)).thenReturn(true); info.getTicketValidVO().setTicketValid(false);
        aop.bindScope(point); verify(verifier,never()).verify(any(),any());
        assertEquals("",response.getHeader("X-DE-LINK-TOKEN"));
    }
    @Test void protectedShareWithoutScopeFailsClosed() throws Throwable {
        when(verifier.required(request)).thenReturn(true); info.getTicketValidVO().setArgs(null);
        assertThrows(IllegalArgumentException.class,()->aop.bindScope(point));
        assertEquals("",response.getHeader("X-DE-LINK-TOKEN"));
    }
    @Test void ordinaryUnscopedShareKeepsOriginalBehavior() throws Throwable {
        info.getTicketValidVO().setArgs("{}"); String token=response.getHeader("X-DE-LINK-TOKEN");
        aop.bindScope(point); assertEquals(token,response.getHeader("X-DE-LINK-TOKEN"));
        verify(verifier,never()).verify(any(),any());
    }
}
