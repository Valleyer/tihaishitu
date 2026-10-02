package cn.tihaishitu.manage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ManageUserStore {
    public record LoginUser(String id, String username, String displayName, String passwordHash,
                            String status, Set<String> roles) {}

    private final JdbcTemplate jdbc;

    public ManageUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LoginUser> findForLogin(String username) {
        List<LoginUser> rows = jdbc.query("""
                SELECT id, username, display_name, password_hash, status
                  FROM app_user
                 WHERE LOWER(username) = LOWER(?)
                """, (result, row) -> {
            String id = result.getString("id");
            return new LoginUser(id, result.getString("username"), result.getString("display_name"),
                    result.getString("password_hash"), result.getString("status"), roles(id));
        }, username);
        return rows.stream().findFirst();
    }

    public Optional<ManageUserView> findView(String username) {
        List<ManageUserView> rows = jdbc.query("""
                SELECT id, username, display_name, status, revision FROM app_user
                 WHERE LOWER(username) = LOWER(?)
                """, (result, row) -> view(result.getString("id"), result), username);
        return rows.stream().findFirst();
    }

    public int adminCount() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM app_user_role r
                  JOIN app_user u ON u.id = r.user_id
                 WHERE r.role_name = 'ADMIN' AND u.status = 'active'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public ManageUserView createInitialAdmin(String username, String displayName, String passwordHash) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO app_user(id, username, display_name, password_hash, status)
                VALUES (?, ?, ?, ?, 'active')
                """, id, username, displayName, passwordHash);
        for (String role : List.of("CONTRIBUTOR", "REVIEWER", "ADMIN")) {
            jdbc.update("INSERT INTO app_user_role(user_id, role_name) VALUES (?, ?)", id, role);
        }
        return new ManageUserView(id, username, displayName, "active", roles(id), 1);
    }

    public List<ManageUserView> findAll() {
        return jdbc.query("""
                SELECT id, username, display_name, status, revision FROM app_user ORDER BY created_at, username
                """, (result, row) -> view(result.getString("id"), result));
    }

    public Optional<ManageUserView> findById(String id) {
        List<ManageUserView> rows = jdbc.query("""
                SELECT id, username, display_name, status, revision FROM app_user WHERE id = ?
                """, (result, row) -> view(id, result), id);
        return rows.stream().findFirst();
    }

    @Transactional
    public ManageUserView create(String username, String displayName, String passwordHash, Set<String> roleNames) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_user(id, username, display_name, password_hash, status) VALUES (?, ?, ?, ?, 'active')",
                id, username, displayName, passwordHash);
        replaceRoles(id, roleNames);
        return findById(id).orElseThrow();
    }

    @Transactional
    public ManageUserView update(String id, String displayName, String status, String passwordHash,
                                 Set<String> roleNames, long expectedRevision) {
        int changed;
        if (passwordHash == null) {
            changed = jdbc.update("""
                    UPDATE app_user SET display_name = ?, status = ?, revision = revision + 1,
                                        updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND revision = ?
                    """, displayName, status, id, expectedRevision);
        } else {
            changed = jdbc.update("""
                    UPDATE app_user SET display_name = ?, status = ?, password_hash = ?, revision = revision + 1,
                                        updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND revision = ?
                    """, displayName, status, passwordHash, id, expectedRevision);
        }
        if (changed == 0) return null;
        replaceRoles(id, roleNames);
        return findById(id).orElseThrow();
    }

    private void replaceRoles(String id, Set<String> roleNames) {
        jdbc.update("DELETE FROM app_user_role WHERE user_id = ?", id);
        for (String role : roleNames) {
            jdbc.update("INSERT INTO app_user_role(user_id, role_name) VALUES (?, ?)", id, role);
        }
    }

    private ManageUserView view(String id, java.sql.ResultSet result) throws java.sql.SQLException {
        return new ManageUserView(id, result.getString("username"), result.getString("display_name"),
                result.getString("status"), roles(id), result.getLong("revision"));
    }

    public Set<String> roles(String userId) {
        return new LinkedHashSet<>(jdbc.query(
                "SELECT role_name FROM app_user_role WHERE user_id = ? ORDER BY role_name",
                (result, row) -> result.getString("role_name"), userId));
    }
}
