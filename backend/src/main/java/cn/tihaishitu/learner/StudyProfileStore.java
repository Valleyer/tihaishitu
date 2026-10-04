package cn.tihaishitu.learner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public class StudyProfileStore {
    public record Profile(String learnerId, String pace, String difficulty, String focusMode, long revision,
                          List<String> selectedBookIds, java.util.Map<String, Integer> weights,
                          List<String> focusedKnowledgePointIds) {}

    private final JdbcTemplate jdbc;
    public StudyProfileStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Profile find(String learnerId) {
        var base = jdbc.query("SELECT pace, difficulty, focus_mode, revision FROM learner_study_profile WHERE learner_id = ?",
                (result, row) -> new Object[]{result.getString("pace"), result.getString("difficulty"),
                        result.getString("focus_mode"), result.getLong("revision")}, learnerId);
        if (base.isEmpty()) throw new IllegalStateException("学习档案不存在。");
        Object[] value = base.get(0);
        List<String> books = jdbc.query("SELECT bank_id FROM learner_selected_book WHERE learner_id = ? ORDER BY created_at, bank_id",
                (result, row) -> result.getString("bank_id"), learnerId);
        java.util.Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT bank_id, weight_value FROM learner_selected_book WHERE learner_id = ? ORDER BY created_at, bank_id",
                (RowCallbackHandler) result -> weights.put(result.getString("bank_id"), result.getInt("weight_value")), learnerId);
        List<String> focus = jdbc.query("SELECT knowledge_point_id FROM learner_focus_knowledge WHERE learner_id = ? ORDER BY sort_order, knowledge_point_id",
                (result, row) -> result.getString("knowledge_point_id"), learnerId);
        return new Profile(learnerId, (String) value[0], (String) value[1], (String) value[2],
                (Long) value[3], books, java.util.Collections.unmodifiableMap(weights), focus);
    }

    public List<String> enabledBookIds(Set<String> requested) {
        if (requested.isEmpty()) return List.of();
        return jdbc.query("SELECT id FROM question_bank WHERE enabled = TRUE AND id IN (" + marks(requested.size()) + ") ORDER BY id",
                (result, row) -> result.getString("id"), requested.toArray());
    }

    public int updateBase(String learnerId, long revision, String pace, String difficulty, String focusMode) {
        return jdbc.update("""
                UPDATE learner_study_profile
                   SET pace = ?, difficulty = ?, focus_mode = ?, revision = revision + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE learner_id = ? AND revision = ?
                """, pace, difficulty, focusMode, learnerId, revision);
    }

    public void replaceBooks(String learnerId, List<String> bookIds, java.util.Map<String, Integer> weights) {
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id = ?", learnerId);
        for (String id : bookIds) jdbc.update(
                "INSERT INTO learner_selected_book(learner_id, bank_id, weight_value) VALUES (?, ?, ?)",
                learnerId, id, weights.getOrDefault(id, 100));
    }

    public void replaceFocus(String learnerId, List<String> knowledgePointIds) {
        jdbc.update("DELETE FROM learner_focus_knowledge WHERE learner_id = ?", learnerId);
        for (int index = 0; index < knowledgePointIds.size(); index++) jdbc.update(
                "INSERT INTO learner_focus_knowledge(learner_id, knowledge_point_id, sort_order) VALUES (?, ?, ?)",
                learnerId, knowledgePointIds.get(index), index);
    }

    private static String marks(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
}
