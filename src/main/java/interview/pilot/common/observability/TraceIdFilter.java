package interview.pilot.common.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Trace-Id";
  public static final String ATTRIBUTE = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = canonicalOrNew(request.getHeader(HEADER));
    String previous = MDC.get(ATTRIBUTE);
    request.setAttribute(ATTRIBUTE, traceId);
    response.setHeader(HEADER, traceId);
    MDC.put(ATTRIBUTE, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (previous == null) MDC.remove(ATTRIBUTE);
      else MDC.put(ATTRIBUTE, previous);
    }
  }

  private String canonicalOrNew(String candidate) {
    if (candidate != null) {
      try {
        String canonical = UUID.fromString(candidate).toString();
        if (canonical.equals(candidate)) return canonical;
      } catch (IllegalArgumentException ignored) {
        // Generate a server-owned canonical identifier below.
      }
    }
    return UUID.randomUUID().toString();
  }
}
