package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class LearnerProgressStore {
    public record BookRow(String id, String name, String description) {}
    public record ChapterRow(String id, String bookId, String parentId, String code, String name) {}
    public record MembershipRow(String bookId, String chapterId, String knowledgePointId,
                                String name, String subject, String section, String chapter) {}
    public record RecentTotals(int gradedAttempts, int distinctKnowledgePoints) {}
    public record DailyRow(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}

    private final JdbcTemplate jdbc;

    public LearnerProgressStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<BookRow> selectedBooks(String learnerId) {
        return jdbc.query("""
                SELECT b.id,b.name,b.description
                  FROM learner_selected_book selected
                  JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                 WHERE selected.learner_id=?
                 ORDER BY selected.created_at,b.id
                """, (rs, row) -> new BookRow(rs.getString("id"), rs.getString("name"),
                rs.getString("description")), learnerId);
    }

    public List<ChapterRow> selectedChapters(String learnerId) {
        return jdbc.query("""
                SELECT c.id,c.bank_id,c.parent_id,c.chapter_code,c.name
                  FROM learner_selected_book selected
                  JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                  JOIN question_bank_chapter c ON c.bank_id=b.id
                 WHERE selected.learner_id=?
                 ORDER BY selected.created_at,c.sort_order,c.id
                """, (rs, row) -> new ChapterRow(rs.getString("id"), rs.getString("bank_id"),
                rs.getString("parent_id"), rs.getString("chapter_code"), rs.getString("name")), learnerId);
    }

    public List<MembershipRow> selectedMemberships(String learnerId) {
        return jdbc.query("""
                SELECT membership.bank_id,membership.chapter_id,k.id,k.name,k.subject_name,
                       k.section_name,k.chapter_name
                  FROM learner_selected_book selected
                  JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                  JOIN question_bank_knowledge membership ON membership.bank_id=b.id
                  JOIN global_knowledge_point k ON k.id=membership.knowledge_point_id AND k.status='active'
                 WHERE selected.learner_id=?
                 ORDER BY selected.created_at,membership.sort_order,k.id
                """, (rs, row) -> new MembershipRow(rs.getString("bank_id"), rs.getString("chapter_id"),
                rs.getString("id"), rs.getString("name"), rs.getString("subject_name"),
                rs.getString("section_name"), rs.getString("chapter_name")), learnerId);
    }

    public RecentTotals recentTotals(String learnerId, Instant from, Instant through) {
        return jdbc.query("""
                SELECT COUNT(*) graded_attempts,
                       COUNT(DISTINCT target_knowledge_point_id) distinct_points
                  FROM study_attempt
                 WHERE learner_id=? AND status='graded' AND answered_at>=? AND answered_at<=?
                """, (rs, row) -> new RecentTotals(rs.getInt("graded_attempts"),
                rs.getInt("distinct_points")), learnerId, Timestamp.from(from), Timestamp.from(through)).get(0);
    }

    public List<DailyRow> recentDaily(String learnerId, Instant from, Instant through) {
        return jdbc.query("""
                SELECT CAST(answered_at AS DATE) study_date,COUNT(*) graded_attempts,
                       COUNT(DISTINCT target_knowledge_point_id) distinct_points
                  FROM study_attempt
                 WHERE learner_id=? AND status='graded' AND answered_at>=? AND answered_at<=?
                 GROUP BY CAST(answered_at AS DATE)
                 ORDER BY study_date
                """, (rs, row) -> new DailyRow(rs.getDate("study_date").toLocalDate(),
                rs.getInt("graded_attempts"), rs.getInt("distinct_points")), learnerId,
                Timestamp.from(from), Timestamp.from(through));
    }
}
