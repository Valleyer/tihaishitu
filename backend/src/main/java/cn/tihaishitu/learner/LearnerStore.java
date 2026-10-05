package cn.tihaishitu.learner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LearnerStore {
    public record Account(String id, String username, String displayName, String passwordHash,
                          String status, long revision) {}

    private final JdbcTemplate jdbc;

    public LearnerStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Account create(String username, String displayName, String passwordHash) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id, username, display_name, password_hash, status) VALUES (?, ?, ?, ?, 'active')",
                id, username, displayName, passwordHash);
        jdbc.update("INSERT INTO learner_study_profile(learner_id, pace, difficulty, focus_mode) VALUES (?, 'normal', 'standard', 'auto')", id);
        jdbc.update("""
                INSERT INTO learner_selected_book(learner_id, bank_id, weight_value)
                SELECT ?, id, weight_value FROM question_bank WHERE enabled = TRUE
                """, id);
        return findById(id).orElseThrow();
    }

    public Optional<Account> findByUsername(String username) {
        return accounts("SELECT id, username, display_name, password_hash, status, revision FROM learner_account WHERE username = ?", username)
                .stream().findFirst();
    }

    public Optional<Account> findById(String id) {
        return accounts("SELECT id, username, display_name, password_hash, status, revision FROM learner_account WHERE id = ?", id)
                .stream().findFirst();
    }

    public void lockForUpdate(String id) {
        jdbc.queryForObject("SELECT id FROM learner_account WHERE id = ? FOR UPDATE", String.class, id);
    }

    public void touchLogin(String id) {
        jdbc.update("UPDATE learner_account SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void createSession(String learnerId, String tokenHash, Instant expiresAt) {
        jdbc.update("INSERT INTO learner_session(id, learner_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), learnerId, tokenHash, Timestamp.from(expiresAt));
    }

    public Optional<Account> findByActiveSession(String tokenHash) {
        List<Account> values = jdbc.query("""
                SELECT a.id, a.username, a.display_name, a.password_hash, a.status, a.revision
                  FROM learner_session s
                  JOIN learner_account a ON a.id = s.learner_id
                 WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP
                   AND a.status = 'active'
                """, (result, row) -> account(result), tokenHash);
        if (!values.isEmpty()) {
            // MySQL 5.7 with explicit_defaults_for_timestamp disabled may add an implicit
            // ON UPDATE clause to the first TIMESTAMP column (expires_at). Assigning the
            // column to itself keeps a last-seen update from expiring the session.
            jdbc.update("UPDATE learner_session SET last_seen_at = CURRENT_TIMESTAMP, expires_at = expires_at WHERE token_hash = ?", tokenHash);
        }
        return values.stream().findFirst();
    }

    public void revokeSession(String tokenHash) {
        jdbc.update("UPDATE learner_session SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ? AND revoked_at IS NULL", tokenHash);
    }

    private List<Account> accounts(String sql, Object... args) {
        return jdbc.query(sql, (result, row) -> account(result), args);
    }

    private static Account account(java.sql.ResultSet result) throws java.sql.SQLException {
        return new Account(result.getString("id"), result.getString("username"),
                result.getString("display_name"), result.getString("password_hash"),
                result.getString("status"), result.getLong("revision"));
    }
}
