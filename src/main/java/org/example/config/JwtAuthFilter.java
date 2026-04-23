package org.example.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String userPoolId;
    private final String clientId;
    private final String region;
    private final String jwksUrl;
    private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();

    public JwtAuthFilter(
            @Value("${aws.cognito.user-pool-id}") String userPoolId,
            @Value("${aws.cognito.client-id}") String clientId,
            @Value("${aws.s3.region}") String region) {
        this.userPoolId = userPoolId;
        this.clientId = clientId;
        this.region = region;
        this.jwksUrl = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId + "/.well-known/jwks.json";
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Skip auth for health check and CORS preflight
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || "/ping".equals(request.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid Authorization header\"}");
            return;
        }

        String token = authHeader.substring(7);
        String email;
        try {
            email = validateAndExtractEmail(token);
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired token\"}");
            return;
        }

        // Wrap request to inject authenticated userId
        HttpServletRequest wrappedRequest = new AuthenticatedRequest(request, email);
        chain.doFilter(wrappedRequest, res);
    }

    private String validateAndExtractEmail(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT format");

        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));

        JsonNode header = mapper.readTree(headerJson);
        JsonNode payload = mapper.readTree(payloadJson);

        // Verify claims
        String kid = header.get("kid").asText();
        String alg = header.get("alg").asText();
        if (!"RS256".equals(alg)) throw new IllegalArgumentException("Unsupported algorithm: " + alg);

        String iss = payload.has("iss") ? payload.get("iss").asText() : "";
        String expectedIss = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
        if (!expectedIss.equals(iss)) throw new IllegalArgumentException("Invalid issuer");

        String tokenUse = payload.has("token_use") ? payload.get("token_use").asText() : "";
        if (!"id".equals(tokenUse)) throw new IllegalArgumentException("Not an id token");

        String aud = payload.has("aud") ? payload.get("aud").asText() : "";
        if (!clientId.equals(aud)) throw new IllegalArgumentException("Invalid audience");

        long exp = payload.has("exp") ? payload.get("exp").asLong() : 0;
        if (System.currentTimeMillis() / 1000 > exp) throw new IllegalArgumentException("Token expired");

        // Verify signature
        RSAPublicKey publicKey = getPublicKey(kid);
        verifySignature(parts[0] + "." + parts[1], parts[2], publicKey);

        // Extract email
        String email = payload.has("email") ? payload.get("email").asText() : null;
        if (email == null || email.isBlank()) throw new IllegalArgumentException("No email in token");

        return email;
    }

    private RSAPublicKey getPublicKey(String kid) throws Exception {
        if (keyCache.containsKey(kid)) return keyCache.get(kid);

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder().uri(URI.create(jwksUrl)).build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode jwks = mapper.readTree(resp.body());
        for (JsonNode key : jwks.get("keys")) {
            if (kid.equals(key.get("kid").asText())) {
                byte[] nBytes = Base64.getUrlDecoder().decode(key.get("n").asText());
                byte[] eBytes = Base64.getUrlDecoder().decode(key.get("e").asText());
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(
                                new BigInteger(1, nBytes),
                                new BigInteger(1, eBytes)));
                keyCache.put(kid, publicKey);
                return publicKey;
            }
        }
        throw new IllegalArgumentException("Key not found: " + kid);
    }

    private void verifySignature(String data, String signature, RSAPublicKey key) throws Exception {
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sigBytes = Base64.getUrlDecoder().decode(signature);
        if (!sig.verify(sigBytes)) throw new IllegalArgumentException("Invalid signature");
    }

    /**
     * Wraps the request to override userId parameter with the authenticated email.
     */
    private static class AuthenticatedRequest extends HttpServletRequestWrapper {
        private final String email;

        public AuthenticatedRequest(HttpServletRequest request, String email) {
            super(request);
            this.email = email;
        }

        @Override
        public String getParameter(String name) {
            if ("userId".equals(name)) return email;
            return super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            if ("userId".equals(name)) return new String[]{email};
            return super.getParameterValues(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> params = new java.util.HashMap<>(super.getParameterMap());
            params.put("userId", new String[]{email});
            return params;
        }

        public String getAuthenticatedEmail() {
            return email;
        }
    }
}
