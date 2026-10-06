package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MySQL 5.7 compatible replacement for the former recursive/window-function migration. */
public class V13__flatten_book_chapters extends BaseJavaMigration {
    private record Chapter(String id, String bankId, String parentId, int sortOrder) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<Chapter> chapters = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,bank_id,parent_id,sort_order FROM question_bank_chapter");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) chapters.add(new Chapter(rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getInt(4)));
        }

        Map<String, List<Chapter>> byBank = new LinkedHashMap<>();
        chapters.forEach(chapter -> byBank.computeIfAbsent(chapter.bankId(), ignored -> new ArrayList<>()).add(chapter));
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE question_bank_chapter SET sort_order=?,parent_id=NULL WHERE id=?")) {
            for (List<Chapter> bankChapters : byBank.values()) {
                Map<String, List<Chapter>> children = new HashMap<>();
                bankChapters.forEach(chapter -> children.computeIfAbsent(chapter.parentId(), ignored -> new ArrayList<>()).add(chapter));
                children.values().forEach(values -> values.sort(Comparator.comparingInt(Chapter::sortOrder)
                        .thenComparing(Chapter::id)));
                List<Chapter> ordered = new ArrayList<>();
                append(children, null, ordered);
                if (ordered.size() != bankChapters.size()) {
                    throw new IllegalStateException("文集章节树存在循环或无效 parent_id，无法扁平化。");
                }
                for (int index = 0; index < ordered.size(); index++) {
                    update.setInt(1, index + 1);
                    update.setString(2, ordered.get(index).id());
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM question_bank_chapter
                 WHERE NOT EXISTS (SELECT 1 FROM question_bank_knowledge membership
                                    WHERE membership.chapter_id=question_bank_chapter.id)
                """)) {
            delete.executeUpdate();
        }
    }

    private static void append(Map<String, List<Chapter>> children, String parentId, List<Chapter> ordered) {
        for (Chapter chapter : children.getOrDefault(parentId, List.of())) {
            ordered.add(chapter);
            append(children, chapter.id(), ordered);
        }
    }
}
