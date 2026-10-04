package cn.tihaishitu.manage;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/manage/auth")
public class ManageAuthController {
    private final LearnerAuthService learnerAuth;
    private final ManageUserStore users;

    public ManageAuthController(LearnerAuthService learnerAuth, ManageUserStore users) {
        this.learnerAuth = learnerAuth;
        this.users = users;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    ManageUserView login(@Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        var learner = learnerAuth.login(body.username(), body.password(), response);
        ManageUserView view = users.findView(learner.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在。"));
        if (view.roles().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有管理后台权限。");
        }
        return view;
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        learnerAuth.logout(request, response);
    }

    @GetMapping("/me")
    ManageUserView me(Authentication authentication) {
        return current(authentication);
    }

    private ManageUserView current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录管理后台。");
        }
        ManageUserView user = users.findView(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在。"));
        if (!"active".equals(user.status())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用。");
        }
        return user;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
