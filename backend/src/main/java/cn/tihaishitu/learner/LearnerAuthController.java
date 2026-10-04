package cn.tihaishitu.learner;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learner")
public class LearnerAuthController {
    private final LearnerAuthService auth;

    public LearnerAuthController(LearnerAuthService auth) { this.auth = auth; }

    @GetMapping("/auth/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public LearnerContext.LearnerPrincipal register(@Valid @RequestBody RegisterRequest request,
                                                    HttpServletResponse response) {
        return auth.register(request.username(), request.displayName(), request.password(), response);
    }

    @PostMapping("/auth/login")
    public LearnerContext.LearnerPrincipal login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletResponse response) {
        return auth.login(request.username(), request.password(), response);
    }

    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) { auth.logout(request, response); }

    @GetMapping("/me") public LearnerContext.LearnerPrincipal me() { return LearnerContext.current(); }

    public record RegisterRequest(@NotBlank String username, String displayName, @NotBlank String password) {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record CsrfResponse(String headerName, String token) {}
}
