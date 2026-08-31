package interview.pilot.auth.jwt;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.GenericFilterBean;

import interview.pilot.auth.application.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 普通 Filter(而非 OncePerRequestFilter):SSE 异步完成后 Tomcat 会触发 ASYNC 派发,
 * 该派发复用同一个请求对象但认证上下文不会自动重建。若用 OncePerRequestFilter,
 * 它会跳过 ASYNC 派发,AuthorizationFilter 就会在响应已提交后误报 Access Denied。
 * 这里在每个派发上重新解析 Authorization 头,代价只是一次 HMAC 校验。
 */
public class JwtAuthenticationFilter extends GenericFilterBean {

  private final JwtTokenService jwtService;

  public JwtAuthenticationFilter(JwtTokenService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  public void doFilter(ServletRequest servletRequest,
                       ServletResponse servletResponse,
                       FilterChain chain)
      throws IOException, ServletException {
    if (servletRequest instanceof HttpServletRequest request) {
      extractToken(request)
          .flatMap(jwtService::verifyAccessToken)
          .ifPresent(this::setAuthentication);
    }
    chain.doFilter(servletRequest, servletResponse);
  }

  private Optional<String> extractToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      return Optional.of(header.substring(7));
    }
    return Optional.empty();
  }

  private void setAuthentication(CurrentUser user) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
  }
}
