package cn.tihaishitu.config;

import cn.tihaishitu.learner.LearnerSessionFilter;
import cn.tihaishitu.learner.LearnerSessionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.util.function.Supplier;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(LearnerSessionProperties.class)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ObjectMapper mapper,
            LearnerSessionFilter learnerSessionFilter) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
                .cors(cors -> {})
                .csrf(configurer -> configurer
                        .csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CookieAndMaskedCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/api/v1/games/**", "/api/v1/admin/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/manage/auth/login", "/api/v1/manage/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/learner/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/manage/**")
                        .hasAnyRole("CONTRIBUTOR", "REVIEWER", "ADMIN")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> writeError(response, mapper, 401, "请先登录管理后台。"))
                        .accessDeniedHandler((request, response, error) -> writeError(response, mapper, 403, "当前账号没有此操作权限。")))
                .logout(logout -> logout.disable())
                .addFilterAfter(learnerSessionFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), java.util.Map.of("message", message));
    }

    /** Accept the raw cookie token used by the learner SPA and the masked token used by the manage SPA. */
    private static final class CookieAndMaskedCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           Supplier<CsrfToken> csrfToken) {
            masked.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            if (header != null && header.equals(csrfToken.getToken())) {
                return plain.resolveCsrfTokenValue(request, csrfToken);
            }
            return masked.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
