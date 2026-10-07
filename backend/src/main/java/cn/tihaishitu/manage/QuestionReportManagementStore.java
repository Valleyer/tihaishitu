package cn.tihaishitu.manage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class QuestionReportManagementStore {
    public record ReportView(String id, String questionId, String attemptId, String reason, String comment,
                             String status, String learnerName, String sourceName, Integer examYear,
                             String questionNumber, Instant createdAt, Instant updatedAt) {}
    private final JdbcTemplate jdbc;
    public QuestionReportManagementStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public PageResult<ReportView> search(String status, String reason, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null && !status.isBlank()) { where.append(" AND r.status=?"); args.add(status); }
        if (reason != null && !reason.isBlank()) { where.append(" AND r.reason=?"); args.add(reason); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question_report r" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<ReportView> rows = jdbc.query("""
                SELECT r.id,r.question_id,r.attempt_id,r.reason,r.comment,r.status,a.display_name,
                       COALESCE(s.display_name,q.source_name,'全服题库') source_name,
                       q.exam_year,q.question_number,r.created_at,r.updated_at
                  FROM question_report r
                  JOIN learner_account a ON a.id=r.learner_id
                  JOIN question_resource q ON q.id=r.question_id
                  LEFT JOIN question_source s ON s.id=q.source_id
                """ + where + " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new ReportView(rs.getString("id"), rs.getString("question_id"),
                        rs.getString("attempt_id"), rs.getString("reason"), rs.getString("comment"),
                        rs.getString("status"), rs.getString("display_name"), rs.getString("source_name"),
                        rs.getObject("exam_year", Integer.class), rs.getString("question_number"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                pageArgs.toArray());
        return PageResult.of(rows, page, size, total == null ? 0 : total);
    }

    public boolean updateStatus(String id, String status) {
        return jdbc.update("UPDATE question_report SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                status, id) > 0;
    }

    public ReportView find(String id) {
        return jdbc.query("""
                SELECT r.id,r.question_id,r.attempt_id,r.reason,r.comment,r.status,a.display_name,
                       COALESCE(s.display_name,q.source_name,'全服题库') source_name,
                       q.exam_year,q.question_number,r.created_at,r.updated_at
                  FROM question_report r JOIN learner_account a ON a.id=r.learner_id
                  JOIN question_resource q ON q.id=r.question_id
                  LEFT JOIN question_source s ON s.id=q.source_id WHERE r.id=?
                """, (rs, row) -> new ReportView(rs.getString("id"), rs.getString("question_id"),
                rs.getString("attempt_id"), rs.getString("reason"), rs.getString("comment"),
                rs.getString("status"), rs.getString("display_name"), rs.getString("source_name"),
                rs.getObject("exam_year", Integer.class), rs.getString("question_number"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id)
                .stream().findFirst().orElse(null);
    }
}
