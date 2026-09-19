package com.fly.pocket_ledger_java.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fly.pocket_ledger_java.config.JwtProperties;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;

class AuthInterceptorTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private JwtUtils jwtUtils;
    private AuthInterceptor interceptor;
    private HandlerMethod handler;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtils = jwtUtils(60);
        interceptor = new AuthInterceptor(jwtUtils, new ObjectMapper());
        handler = new HandlerMethod(this, getClass().getMethod("endpoint"));
    }

    public void endpoint() {
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void validTokenSetsUserAndCleansUpAfterRequest() throws Exception {
        bearer(jwtUtils.generateToken(1L, "reed"));

        assertTrue(interceptor.preHandle(request, response, handler));
        assertEquals(Long.valueOf(1L), UserContext.getUserId());
        assertEquals("reed", UserContext.get().getUsername());

        interceptor.afterCompletion(request, response, handler, null);
        assertNull(UserContext.get());
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        assertUnauthorized();
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        bearer("invalid-token");
        assertUnauthorized();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        bearer(jwtUtils(-1).generateToken(1L, "reed"));
        assertUnauthorized();
    }

    @Test
    void corsPreflightDoesNotRequireToken() throws Exception {
        request.setMethod("OPTIONS");
        assertTrue(interceptor.preHandle(request, response, handler));
    }

    private void bearer(String token) {
        request.addHeader("Authorization", "Bearer " + token);
    }

    private void assertUnauthorized() throws Exception {
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(401, response.getStatus());
        assertEquals(401, new ObjectMapper().readTree(response.getContentAsByteArray()).get("code").asInt());
        assertNull(UserContext.get());
    }

    private JwtUtils jwtUtils(long expireMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("auth-interceptor-test-secret-32-bytes-minimum");
        properties.setExpireMinutes(expireMinutes);
        return new JwtUtils(properties);
    }
}
