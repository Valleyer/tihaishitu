package cn.tihaishitu.manage;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ManageUserDeletionService {
    private final JdbcTemplate jdbc;
    private final ManageUserStore users;
    private final KnowledgeManagementStore audit;

    public ManageUserDeletionService(
            JdbcTemplate jdbc, ManageUserStore users, KnowledgeManagementStore audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public void delete(String targetId, String actorId) {
        ManageUserView target = users.findById(targetId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在。"));
        if (targetId.equals(actorId)) bad("不能删除当前登录账号。");
        if ("active".equals(target.status()) && target.roles().contains("ADMIN") && users.adminCount() <= 1) {
            bad("不能删除最后一个有效管理员。");
        }

        int rolesCount = count("SELECT COUNT(*) FROM learner_account_role WHERE learner_id=?", targetId);
        int attemptCount = count("SELECT COUNT(*) FROM study_attempt WHERE learner_id=?", targetId);
        int evidenceCount = count("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=?", targetId);
        int worldStateCount = count("SELECT COUNT(*) FROM learner_world_state WHERE learner_id=?", targetId);

        Set<String> identities = new LinkedHashSet<>();
        identities.add(targetId);
        identities.addAll(jdbc.query("SELECT id FROM app_user WHERE id=? OR LOWER(username)=LOWER(?)",
                (row, index) -> row.getString("id"), targetId, target.username()));
        for (String identity : identities) {
            jdbc.update("UPDATE question_resource SET created_by=NULL WHERE created_by=?", identity);
            jdbc.update("UPDATE question_resource SET updated_by=NULL WHERE updated_by=?", identity);
            jdbc.update("UPDATE question_resource SET reviewed_by=NULL WHERE reviewed_by=?", identity);
            jdbc.update("UPDATE question_resource_knowledge SET created_by=NULL WHERE created_by=?", identity);
        }
        jdbc.update("UPDATE content_audit_log SET actor_learner_id=NULL WHERE actor_learner_id=?", targetId);
        jdbc.update("UPDATE knowledge_merge_history SET actor_learner_id=NULL WHERE actor_learner_id=?", targetId);

        jdbc.update("UPDATE learner_practice_session SET current_attempt_id=NULL WHERE learner_id=?", targetId);
        jdbc.update("UPDATE study_attempt SET diagnosis_session_id=NULL, practice_session_id=NULL WHERE learner_id=?", targetId);
        jdbc.update("UPDATE learner_diagnosis_session SET practice_session_id=NULL WHERE learner_id=?", targetId);
        jdbc.update("DELETE FROM learner_knowledge_evidence WHERE learner_id=?", targetId);
        jdbc.update("DELETE FROM answer_record WHERE learner_id=?", targetId);
        jdbc.update("DELETE FROM learner_diagnosis_session WHERE learner_id=?", targetId);
        jdbc.update("DELETE FROM learner_practice_session WHERE learner_id=?", targetId);
        jdbc.update("DELETE FROM study_attempt WHERE learner_id=?", targetId);
        jdbc.update("DELETE FROM learner_account WHERE id=?", targetId);

        for (String legacyId : identities) {
            jdbc.update("UPDATE content_audit_log SET actor_user_id=NULL WHERE actor_user_id=?", legacyId);
            jdbc.update("UPDATE knowledge_merge_history SET actor_user_id=NULL WHERE actor_user_id=?", legacyId);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", legacyId);
            jdbc.update("DELETE FROM app_user WHERE id=?", legacyId);
        }

        audit.audit(actorId, "USER_DELETED", "learner_account", targetId, Map.of(
                "rolesCount", rolesCount,
                "attemptCount", attemptCount,
                "evidenceCount", evidenceCount,
                "worldStateCount", worldStateCount));
    }

    private int count(String sql, String id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id);
        return count == null ? 0 : count;
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
