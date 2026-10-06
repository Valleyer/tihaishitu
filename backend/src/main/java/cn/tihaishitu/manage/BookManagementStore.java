package cn.tihaishitu.manage;

import cn.tihaishitu.learning.TrainableKnowledge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class BookManagementStore {
    public record BookView(String id, String name, String description, boolean enabled, long revision,
                           int membershipCount, int trainableKnowledgePointCount,
                           int publishedQuestionCount, int chapterCount, int selectedLearnerCount) {}
    public record ChapterView(String id, String parentId, String code, String name,
                              String description, int sortOrder, long revision) {}
    public record BookDetail(BookView book, List<ChapterView> chapters) {}

    private final JdbcTemplate jdbc;

    public BookManagementStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<BookView> findAll() {
        return jdbc.query(baseSelect() + " ORDER BY b.created_at,b.id", (rs, row) -> book(rs));
    }

    public Optional<BookView> find(String id) {
        return jdbc.query(baseSelect() + " WHERE b.id=?", (rs, row) -> book(rs), id)
                .stream().findFirst();
    }

    public Optional<BookDetail> detail(String id) {
        return find(id).map(book -> new BookDetail(book, jdbc.query("""
                SELECT id,parent_id,chapter_code,name,description,sort_order,revision
                  FROM question_bank_chapter WHERE bank_id=? ORDER BY sort_order,id
                """, (rs, row) -> new ChapterView(rs.getString("id"), rs.getString("parent_id"),
                rs.getString("chapter_code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("sort_order"), rs.getLong("revision")), id)));
    }

    public void create(String id, String name, String description, boolean enabled) {
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,?,?,100,1)",
                id, name, description, enabled);
    }

    public Optional<ChapterView> findChapter(String bookId, String chapterId) {
        return jdbc.query("""
                SELECT id,parent_id,chapter_code,name,description,sort_order,revision
                  FROM question_bank_chapter WHERE bank_id=? AND id=?
                """, (rs, row) -> new ChapterView(rs.getString("id"), rs.getString("parent_id"),
                rs.getString("chapter_code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("sort_order"), rs.getLong("revision")), bookId, chapterId).stream().findFirst();
    }

    public void createChapter(String id, String bookId, String parentId, String code,
                              String name, String description) {
        Integer sort = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sort_order),-1)+1 FROM question_bank_chapter
                 WHERE bank_id=? AND ((parent_id IS NULL AND ? IS NULL) OR parent_id=?)
                """, Integer.class, bookId, parentId, parentId);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,parent_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,?,?,?,?,?,1)
                """, id, bookId, parentId, code, name, description, sort == null ? 0 : sort);
    }

    public int childCount(String bookId, String chapterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id=? AND parent_id=?",
                Integer.class, bookId, chapterId);
        return count == null ? 0 : count;
    }

    public int chapterMembershipCount(String bookId, String chapterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id=? AND chapter_id=?",
                Integer.class, bookId, chapterId);
        return count == null ? 0 : count;
    }

    public int deleteChapter(String bookId, String chapterId) {
        return jdbc.update("DELETE FROM question_bank_chapter WHERE bank_id=? AND id=?", bookId, chapterId);
    }

    public List<String> siblingIds(String bookId, String parentId) {
        return jdbc.query("""
                SELECT id FROM question_bank_chapter
                 WHERE bank_id=? AND ((parent_id IS NULL AND ? IS NULL) OR parent_id=?)
                 ORDER BY sort_order,id
                """, (rs, row) -> rs.getString("id"), bookId, parentId, parentId);
    }

    public void reorder(String bookId, String parentId, List<String> chapterIds) {
        for (int index = 0; index < chapterIds.size(); index++) {
            jdbc.update("""
                    UPDATE question_bank_chapter SET sort_order=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                     WHERE bank_id=? AND id=? AND ((parent_id IS NULL AND ? IS NULL) OR parent_id=?)
                    """, index, bookId, chapterIds.get(index), parentId, parentId);
        }
    }

    public int update(String id, String name, String description, boolean enabled, long revision) {
        return jdbc.update("""
                UPDATE question_bank SET name=?,description=?,enabled=?,revision=revision+1,
                    updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?
                """, name, description, enabled, id, revision);
    }

    public int updateChapter(String bookId, String chapterId, String name, String description, long revision) {
        return jdbc.update("""
                UPDATE question_bank_chapter SET name=?,description=?,revision=revision+1,
                    updated_at=CURRENT_TIMESTAMP WHERE bank_id=? AND id=? AND revision=?
                """, name, description, bookId, chapterId, revision);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM question_bank WHERE id=?", id);
    }

    private static String baseSelect() {
        return """
                SELECT b.id,b.name,b.description,b.enabled,b.revision,
                       (SELECT COUNT(*) FROM question_bank_knowledge bk WHERE bk.bank_id=b.id) membership_count,
                       (SELECT COUNT(DISTINCT bk.knowledge_point_id)
                          FROM question_bank_knowledge bk JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id
                         WHERE bk.bank_id=b.id AND k.status='active' AND %s) trainable_count,
                       (SELECT COUNT(DISTINCT q.id)
                          FROM question_bank_knowledge bk
                          JOIN question_resource_knowledge qk ON qk.knowledge_point_id=bk.knowledge_point_id
                          JOIN question_resource q ON q.id=qk.question_id
                         WHERE bk.bank_id=b.id AND qk.relation_role='core' AND q.status='published'
                           AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')) question_count,
                       (SELECT COUNT(*) FROM question_bank_chapter c WHERE c.bank_id=b.id) chapter_count,
                       (SELECT COUNT(*) FROM learner_selected_book s WHERE s.bank_id=b.id) selected_count
                  FROM question_bank b
                """.formatted(TrainableKnowledge.exists("k"));
    }

    private static BookView book(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BookView(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("enabled"), rs.getLong("revision"), rs.getInt("membership_count"),
                rs.getInt("trainable_count"), rs.getInt("question_count"), rs.getInt("chapter_count"),
                rs.getInt("selected_count"));
    }
}
