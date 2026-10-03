package cn.tihaishitu.config;

import cn.tihaishitu.manage.ManageUserStore;
import cn.tihaishitu.learner.LearnerSessionFilter;
import cn.tihaishitu.learner.LearnerSessionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(LearnerSessionProperties.class)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService manageUserDetails(ManageUserStore users) {
        return username -> users.findForLogin(username)
                .map(user -> User.withUsername(user.username())
                        .password(user.passwordHash())
                        .disabled(!"active".equals(user.status()))
                        .authorities(user.roles().stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .toList())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider::authenticate;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ObjectMapper mapper, ActiveManageAccountFilter activeManageAccountFilter,
            LearnerSessionFilter learnerSessionFilter) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
                .cors(cors -> {})
                .csrf(configurer -> configurer
                        .csrfTokenRepository(csrf)
                        .ignoringRequestMatchers("/api/v1/games/**", "/api/v1/admin/**",
                                "/api/v1/learner/**", "/api/v1/worlds/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/manage/auth/login", "/api/v1/manage/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/manage/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> writeError(response, mapper, 401, "请先登录管理后台。"))
                        .accessDeniedHandler((request, response, error) -> writeError(response, mapper, 403, "当前账号没有此操作权限。")))
                .logout(logout -> logout.disable())
                .addFilterAfter(learnerSessionFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(activeManageAccountFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), java.util.Map.of("message", message));
    }
}
