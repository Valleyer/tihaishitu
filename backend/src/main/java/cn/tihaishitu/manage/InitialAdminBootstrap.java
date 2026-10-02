package cn.tihaishitu.manage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class InitialAdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);
    private final ManageUserStore users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public InitialAdminBootstrap(
            ManageUserStore users,
            PasswordEncoder passwordEncoder,
            @Value("${app.initial-admin.username:}") String username,
            @Value("${app.initial-admin.password:}") String password,
            @Value("${app.initial-admin.display-name:初始管理员}") String displayName) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.displayName = displayName == null || displayName.isBlank() ? "初始管理员" : displayName.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) return;
        if (username.isBlank() || password.isBlank()) {
            log.warn("初始管理员未创建：用户名与密码环境变量必须同时配置。");
            return;
        }
        if (users.adminCount() > 0) return;
        users.createInitialAdmin(username, displayName, passwordEncoder.encode(password));
        log.info("已创建首个管理后台管理员账号：{}", username);
    }
}
