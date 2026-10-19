package kr.co.cking.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import kr.co.cking.creator.domain.ContentIntro;

/** An opaque Bearer token is accepted only by the internal content route chain. */
public class ContentTagAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final AuthenticationEntryPoint entryPoint;
    public ContentTagAuthenticationFilter(String hash, AuthenticationEntryPoint entryPoint) {
        expected = hash.isBlank() ? new byte[0] : HexFormat.of().parseHex(hash);
        if (expected.length != 0 && expected.length != 32) throw new IllegalStateException("Invalid content credential hash");
        this.entryPoint = entryPoint;
    }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var headers = request.getHeaders("Authorization");
        String value = headers.hasMoreElements() ? headers.nextElement() : "";
        if (headers.hasMoreElements() || !value.startsWith("Bearer ") || value.length() <= 7 || expected.length == 0
                || !MessageDigest.isEqual(expected, HexFormat.of().parseHex(ContentIntro.sha256(value.substring(7))))) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid content credential"));
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("content-worker", null,
                List.of(new SimpleGrantedAuthority("CONTENT_TAG_BATCH"))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
