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

    public ManageUserStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<LoginUser> findForLogin(String username) {
        return jdbc.query("""
                SELECT id, username, display_name, password_hash, status
                  FROM learner_account WHERE LOWER(username) = LOWER(?)
                """, (result, row) -> {
            String id = result.getString("id");
            return new LoginUser(id, result.getString("username"), result.getString("display_name"),
                    result.getString("password_hash"), result.getString("status"), roles(id));
        }, username).stream().findFirst();
    }

    public Optional<ManageUserView> findView(String username) {
        return jdbc.query("""
                SELECT id, username, display_name, status, revision FROM learner_account
                 WHERE LOWER(username) = LOWER(?)
                """, (result, row) -> view(result.getString("id"), result), username).stream().findFirst();
    }

    public int adminCount() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM learner_account_role r
                  JOIN learner_account u ON u.id = r.learner_id
                 WHERE r.role_name = 'ADMIN' AND u.status = 'active'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public ManageUserView createInitialAdmin(String username, String displayName, String passwordHash) {
        String id = findView(username).map(ManageUserView::id)
                .orElseGet(() -> createAccount(username, displayName, passwordHash));
        replaceRoles(id, Set.of("CONTRIBUTOR", "REVIEWER", "ADMIN"));
        return findById(id).orElseThrow();
    }

    public List<ManageUserView> findAll() {
        return jdbc.query("""
                SELECT id, username, display_name, status, revision
                  FROM learner_account ORDER BY created_at, username
                """, (result, row) -> view(result.getString("id"), result));
    }

    public Optional<ManageUserView> findById(String id) {
        return jdbc.query("""
                SELECT id, username, display_name, status, revision FROM learner_account WHERE id = ?
                """, (result, row) -> view(id, result), id).stream().findFirst();
    }

    @Transactional
    public ManageUserView create(String username, String displayName, String passwordHash, Set<String> roleNames) {
        String id = createAccount(username, displayName, passwordHash);
        replaceRoles(id, roleNames);
        return findById(id).orElseThrow();
    }

    @Transactional
    public ManageUserView update(String id, String displayName, String status, String passwordHash,
                                 Set<String> roleNames, long expectedRevision) {
        int changed;
        if (passwordHash == null) {
            changed = jdbc.update("""
                    UPDATE learner_account SET display_name = ?, status = ?, revision = revision + 1,
                                               updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND revision = ?
                    """, displayName, status, id, expectedRevision);
        } else {
            changed = jdbc.update("""
                    UPDATE learner_account SET display_name = ?, status = ?, password_hash = ?,
                                               revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND revision = ?
                    """, displayName, status, passwordHash, id, expectedRevision);
        }
        if (changed == 0) return null;
        replaceRoles(id, roleNames);
        return findById(id).orElseThrow();
    }

    private String createAccount(String username, String displayName, String passwordHash) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO learner_account(id, username, display_name, password_hash, status)
                VALUES (?, ?, ?, ?, 'active')
                """, id, username.trim().toLowerCase(java.util.Locale.ROOT), displayName, passwordHash);
        jdbc.update("""
                INSERT INTO learner_study_profile(learner_id, pace, difficulty, focus_mode)
                VALUES (?, 'normal', 'standard', 'auto')
                """, id);
        jdbc.update("""
                INSERT INTO learner_selected_book(learner_id, bank_id, weight_value)
                SELECT ?, id, weight_value FROM question_bank WHERE enabled = TRUE
                """, id);
        return id;
    }

    private void replaceRoles(String id, Set<String> roleNames) {
        jdbc.update("DELETE FROM learner_account_role WHERE learner_id = ?", id);
        for (String role : roleNames) {
            jdbc.update("INSERT INTO learner_account_role(learner_id, role_name) VALUES (?, ?)", id, role);
        }
    }

    private ManageUserView view(String id, java.sql.ResultSet result) throws java.sql.SQLException {
        return new ManageUserView(id, result.getString("username"), result.getString("display_name"),
                result.getString("status"), roles(id), result.getLong("revision"));
    }

    public Set<String> roles(String learnerId) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT role_name FROM learner_account_role WHERE learner_id = ? ORDER BY role_name
                """, (result, row) -> result.getString("role_name"), learnerId));
    }
}
