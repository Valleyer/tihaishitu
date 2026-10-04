package cn.tihaishitu.learner;

import cn.tihaishitu.manage.ManageUserStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class LearnerSessionFilter extends OncePerRequestFilter {
    private final LearnerAuthService auth;
    private final ManageUserStore users;
    private final ObjectMapper mapper;

    public LearnerSessionFilter(LearnerAuthService auth, ManageUserStore users, ObjectMapper mapper) {
        this.auth = auth;
        this.users = users;
        this.mapper = mapper;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        LearnerContext.LearnerPrincipal learner = auth.resolve(request);
        if (learner != null) {
            LearnerContext.set(learner);
            var authorities = users.roles(learner.id()).stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(learner.username(), learner.id(), authorities));
        }
        try {
            if (requiresLearner(request.getRequestURI(), request.getMethod()) && learner == null) {
                response.setStatus(401);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getWriter(), Map.of("message", "请先登录学习账号。"));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            LearnerContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean requiresLearner(String uri, String method) {
        if (!uri.startsWith("/api/v1/")) return false;
        if (uri.equals("/api/v1/learner/auth/register") || uri.equals("/api/v1/learner/auth/login")
                || uri.equals("/api/v1/learner/auth/csrf")) return false;
        return uri.equals("/api/v1/bootstrap") || uri.startsWith("/api/v1/learner/")
                || uri.startsWith("/api/v1/learning/") || uri.startsWith("/api/v1/worlds/");
    }
}
