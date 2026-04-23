package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class JwtAuthFilterTest {

    private JwtAuthFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        filter = new JwtAuthFilter("ap-south-1_nrTKj4NxJ", "1118lpe6tgtj7he23p8kk7duse", "ap-south-1");
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @Test
    void pingSkipsAuth() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/ping");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void optionsSkipsAuth() throws Exception {
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getRequestURI()).thenReturn("/api/tables");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void missingAuthHeaderReturns401() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/tables");
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        assertTrue(responseBody.toString().contains("Missing or invalid Authorization header"));
    }

    @Test
    void invalidBearerPrefixReturns401() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/tables");
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/tables");
        when(request.getHeader("Authorization")).thenReturn("Bearer not.a.valid.jwt");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        assertTrue(responseBody.toString().contains("Invalid or expired token"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/tables");
        // Craft a JWT with expired timestamp (exp=0), valid base64 but will fail validation
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"kid\":\"testkey\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_nrTKj4NxJ\",\"token_use\":\"id\",\"aud\":\"1118lpe6tgtj7he23p8kk7duse\",\"exp\":0,\"email\":\"test@test.com\"}".getBytes());
        String fakeToken = header + "." + payload + ".fakesig";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + fakeToken);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
    }
}
