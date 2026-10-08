package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 进度与统计共用的范围读取。
 *
 * <p>PR7 起，``study_attempt`` 的活动事实统一由 {@link LearnerActivityStatsService}
 * 派生；本 Store 只负责按当前 Selected Books 读取正式目录
 * （{@code question_bank → question_bank_chapter → question_bank_knowledge}）
 * 的去重 KnowledgePoint 范围，不再各自聚合答题数值。</p>
 */
@Repository
public class LearnerProgressStore {
    public record BookRow(String id, String name, String description) {}
    public record ChapterRow(String id, String bookId, String code, String name) {}
    public record MembershipRow(String bookId, String chapterId, String knowledgePointId,
                                String name, String subject, String section, String chapter,
                                String bookName, String chapterName) {}

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

    /**
     * 当前范围内可学习的去重 membership。
     *
     * <p>多本文集共享同一 KnowledgePoint 时会返回多行，调用方必须按 KnowledgePoint ID
     * 去重（{@link LearnerProgressService#scopedKnowledgePointIds}），不能直接按行数统计。</p>
     */
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
}
