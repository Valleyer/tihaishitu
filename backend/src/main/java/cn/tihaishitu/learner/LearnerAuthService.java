package cn.tihaishitu.learner;

import cn.tihaishitu.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class LearnerAuthService {
    public static final String COOKIE = "THS_LEARNER_SESSION";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LearnerStore store;
    private final PasswordEncoder passwords;
    private final LearnerSessionProperties properties;

    public LearnerAuthService(LearnerStore store, PasswordEncoder passwords, LearnerSessionProperties properties) {
        this.store = store;
        this.passwords = passwords;
        this.properties = properties;
    }

    public LearnerContext.LearnerPrincipal register(String username, String displayName, String password,
                                                     HttpServletResponse response) {
        String normalized = normalizeUsername(username);
        if (password == null || password.length() < 8 || password.length() > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码长度须为 8–72 位。");
        String shown = displayName == null || displayName.isBlank() ? normalized : displayName.trim();
        if (shown.length() > 120) throw new ApiException(HttpStatus.BAD_REQUEST, "显示名称过长。");
        try {
            LearnerStore.Account account = store.create(normalized, shown, passwords.encode(password));
            return startSession(account, response);
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "该用户名已被使用。");
        }
    }

    public LearnerContext.LearnerPrincipal login(String username, String password, HttpServletResponse response) {
        String normalized;
        try { normalized = normalizeUsername(username); }
        catch (ApiException error) { throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"); }
        LearnerStore.Account account = store.findByUsername(normalized)
                .filter(value -> "active".equals(value.status()))
                .filter(value -> passwords.matches(password == null ? "" : password, value.passwordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        store.touchLogin(account.id());
        return startSession(account, response);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookie(request);
        if (raw != null) store.revokeSession(hash(raw));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    public LearnerContext.LearnerPrincipal resolve(HttpServletRequest request) {
        String raw = cookie(request);
        if (raw == null) return null;
        return store.findByActiveSession(hash(raw)).map(this::principal).orElse(null);
    }

    private LearnerContext.LearnerPrincipal startSession(LearnerStore.Account account, HttpServletResponse response) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.createSession(account.id(), hash(raw), Instant.now().plusSeconds(properties.maxAgeSeconds()));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(raw, properties.maxAgeSeconds()).toString());
        return principal(account);
    }

    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(properties.secure())
                .sameSite("Lax").path("/").maxAge(Duration.ofSeconds(maxAge)).build();
    }

    private LearnerContext.LearnerPrincipal principal(LearnerStore.Account account) {
        return new LearnerContext.LearnerPrincipal(account.id(), account.username(), account.displayName(), account.revision());
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9_\\-.]{3,80}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "用户名须为 3–80 位字母、数字、点、横线或下划线。");
        return value;
    }

    public static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
