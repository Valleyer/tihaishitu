package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Repository
public class LearnerProgressStore {
    public record BookRow(String id, String name, String description) {}
    public record ChapterRow(String id, String bookId, String code, String name) {}
    public record MembershipRow(String bookId, String chapterId, String knowledgePointId,
                                String name, String subject, String section, String chapter,
                                String bookName, String chapterName) {}
    public record RecentTotals(int gradedAttempts, int distinctKnowledgePoints) {}
    public record DailyRow(LocalDate date, int gradedAttempts, Set<String> knowledgePointIds) {}
    public record RecentAttempt(Instant answeredAt, String knowledgePointId) {}

    private final JdbcTemplate jdbc;

    public LearnerProgressStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<BookRow> selectedBooks(String learnerId) {
        return jdbc.query("""
                SELECT b.id,b.name,b.description
                  FROM learner_selected_book selected
                 JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                 WHERE selected.learner_id=?
                   AND EXISTS (
                       SELECT 1 FROM question_bank_knowledge visible_membership
                       JOIN global_knowledge_point k ON k.id=visible_membership.knowledge_point_id
                       WHERE visible_membership.bank_id=b.id AND k.status='active'
                         AND """ + " " + TrainableKnowledge.exists("k") + """
                   )
                 ORDER BY selected.created_at,b.id
                """, (rs, row) -> new BookRow(rs.getString("id"), rs.getString("name"),
                rs.getString("description")), learnerId);
    }

    public List<ChapterRow> selectedChapters(String learnerId) {
        return jdbc.query("""
                SELECT c.id,c.bank_id,c.chapter_code,c.name
                  FROM learner_selected_book selected
                  JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                  JOIN question_bank_chapter c ON c.bank_id=b.id
                 WHERE selected.learner_id=?
                 ORDER BY selected.created_at,c.sort_order,c.id
                """, (rs, row) -> new ChapterRow(rs.getString("id"), rs.getString("bank_id"),
                rs.getString("chapter_code"), rs.getString("name")), learnerId);
    }

    public List<MembershipRow> selectedMemberships(String learnerId) {
        return jdbc.query("""
                SELECT membership.bank_id,membership.chapter_id,k.id,k.name,k.subject_name,
                       k.section_name,catalog.name catalog_chapter,b.name catalog_book
                  FROM learner_selected_book selected
                  JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                  JOIN question_bank_knowledge membership ON membership.bank_id=b.id
                  JOIN question_bank_chapter catalog ON catalog.bank_id=membership.bank_id
                                                    AND catalog.id=membership.chapter_id
                  JOIN global_knowledge_point k ON k.id=membership.knowledge_point_id AND k.status='active'
                 WHERE selected.learner_id=?
                   AND """ + " " + TrainableKnowledge.exists("k") + """
                 ORDER BY selected.created_at,membership.sort_order,k.id
                """, (rs, row) -> new MembershipRow(rs.getString("bank_id"), rs.getString("chapter_id"),
                rs.getString("id"), rs.getString("name"), rs.getString("subject_name"),
                rs.getString("section_name"), rs.getString("catalog_chapter"),
                rs.getString("catalog_book"), rs.getString("catalog_chapter")), learnerId);
    }

    public RecentTotals recentTotals(String learnerId, Instant from, Instant through) {
        return jdbc.query("""
                SELECT COUNT(*) graded_attempts,
                       COUNT(DISTINCT target_knowledge_point_id) distinct_points
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.answered_at>=? AND a.answered_at<=?
                   AND q.status='published' AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                """, (rs, row) -> new RecentTotals(rs.getInt("graded_attempts"),
                rs.getInt("distinct_points")), learnerId, Timestamp.from(from), Timestamp.from(through)).get(0);
    }

    public List<RecentAttempt> recentAttempts(String learnerId, Instant from, Instant through) {
        return jdbc.query("""
                SELECT answered_at,target_knowledge_point_id
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.answered_at>=? AND a.answered_at<=?
                   AND q.status='published' AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                 ORDER BY answered_at,a.id
                """, (rs, row) -> new RecentAttempt(rs.getTimestamp("answered_at").toInstant(),
                rs.getString("target_knowledge_point_id")), learnerId,
                Timestamp.from(from), Timestamp.from(through));
    }
}
