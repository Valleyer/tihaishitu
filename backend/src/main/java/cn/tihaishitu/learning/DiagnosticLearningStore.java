package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DiagnosticLearningStore {
    public record Session(String id, String learnerId, String worldId, String rootAttemptId,
                          String targetKnowledgePointId, String status, String resolution,
                          boolean hasUnavailableDependency, long revision) {}
    public record Dependency(String diagnosisId, String knowledgePointId, int sortOrder, String status) {}
    public record CanonicalPoint(String id, String status, String mergedIntoId) {}

    private final JdbcTemplate jdbc;

    public DiagnosticLearningStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CanonicalPoint> point(String id) {
        return jdbc.query("SELECT id,status,merged_into_id FROM global_knowledge_point WHERE id=?",
                (rs, row) -> new CanonicalPoint(rs.getString("id"), rs.getString("status"),
                        rs.getString("merged_into_id")), id).stream().findFirst();
    }

    public void createSession(String id, String learnerId, String worldId, String rootAttemptId,
                              String targetId, String status, boolean hasUnavailable) {
        jdbc.update("""
                INSERT INTO learner_diagnosis_session(
                    id,learner_id,world_id,root_attempt_id,target_knowledge_point_id,status,
                    has_unavailable_dependency,revision)
                VALUES (?,?,?,?,?,?,?,1)
                """, id, learnerId, worldId, rootAttemptId, targetId, status, hasUnavailable);
    }

    public void addDependency(String diagnosisId, String pointId, int sortOrder, String status) {
        jdbc.update("""
                INSERT INTO learner_diagnosis_dependency(diagnosis_id,knowledge_point_id,sort_order,status)
                VALUES (?,?,?,?)
                """, diagnosisId, pointId, sortOrder, status);
    }

    public Optional<Session> findByRootAttempt(String rootAttemptId) {
        return sessions("WHERE root_attempt_id=?", rootAttemptId).stream().findFirst();
    }

    public Optional<Session> find(String id) {
        return sessions("WHERE id=?", id).stream().findFirst();
    }

    public Session lock(String id) {
        return sessions("WHERE id=? FOR UPDATE", id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("诊断会话不存在。"));
    }

    public List<Dependency> dependencies(String diagnosisId) {
        return jdbc.query("""
                SELECT diagnosis_id,knowledge_point_id,sort_order,status
                  FROM learner_diagnosis_dependency WHERE diagnosis_id=?
                 ORDER BY sort_order,knowledge_point_id
                """, (rs, row) -> new Dependency(rs.getString("diagnosis_id"),
                rs.getString("knowledge_point_id"), rs.getInt("sort_order"), rs.getString("status")), diagnosisId);
    }

    public void updateDependency(String diagnosisId, String pointId, String expected, String status) {
        int changed = jdbc.update("""
                UPDATE learner_diagnosis_dependency SET status=?,updated_at=CURRENT_TIMESTAMP
                 WHERE diagnosis_id=? AND knowledge_point_id=? AND status=?
                """, status, diagnosisId, pointId, expected);
        if (changed != 1) throw new IllegalStateException("诊断依赖状态已经变化。 ");
    }

    public void markUnavailable(String diagnosisId, String pointId) {
        updateDependency(diagnosisId, pointId, "pending", "unavailable");
        jdbc.update("""
                UPDATE learner_diagnosis_session
                   SET has_unavailable_dependency=TRUE,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, diagnosisId);
    }

    public void transition(String id, String expected, String status) {
        int changed = jdbc.update("""
                UPDATE learner_diagnosis_session
                   SET status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND status=?
                """, status, id, expected);
        if (changed != 1) throw new IllegalStateException("诊断会话状态已经变化。 ");
    }

    public void resolve(String id, String expected, String resolution, Instant at) {
        int changed = jdbc.update("""
                UPDATE learner_diagnosis_session
                   SET status='resolved',resolution=?,resolved_at=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND status=?
                """, resolution, Timestamp.from(at), id, expected);
        if (changed != 1) throw new IllegalStateException("诊断会话状态已经变化。 ");
    }

    public void abandon(String id, Instant at) {
        jdbc.update("""
                UPDATE learner_diagnosis_session
                   SET status='abandoned',resolution='abandoned',resolved_at=?,revision=revision+1,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND status NOT IN ('resolved','abandoned')
                """, Timestamp.from(at), id);
    }

    private List<Session> sessions(String predicate, Object... args) {
        return jdbc.query("""
                SELECT id,learner_id,world_id,root_attempt_id,target_knowledge_point_id,status,resolution,
                       has_unavailable_dependency,revision
                  FROM learner_diagnosis_session %s
                """.formatted(predicate), (rs, row) -> new Session(rs.getString("id"),
                rs.getString("learner_id"), rs.getString("world_id"), rs.getString("root_attempt_id"),
                rs.getString("target_knowledge_point_id"), rs.getString("status"), rs.getString("resolution"),
                rs.getBoolean("has_unavailable_dependency"), rs.getLong("revision")), args);
    }
}
