package cn.tihaishitu.questionimage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class QuestionImageStore {
    public record Asset(String id, String storageName, String originalName, String contentType,
                        long byteSize, String sha256, String createdBy, Instant createdAt) {
        public String url() { return QuestionImageUrls.url(id); }
    }

    private final JdbcTemplate jdbc;

    public QuestionImageStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(Asset asset) {
        jdbc.update("""
                INSERT INTO question_image_asset(
                    id,storage_name,original_name,content_type,byte_size,sha256,created_by)
                VALUES (?,?,?,?,?,?,?)
                """, asset.id(), asset.storageName(), asset.originalName(), asset.contentType(),
                asset.byteSize(), asset.sha256(), asset.createdBy());
    }

    public Optional<Asset> find(String id) {
        return jdbc.query("""
                SELECT id,storage_name,original_name,content_type,byte_size,sha256,created_by,created_at
                  FROM question_image_asset WHERE id=?
                """, (row, index) -> new Asset(row.getString("id"), row.getString("storage_name"),
                row.getString("original_name"), row.getString("content_type"), row.getLong("byte_size"),
                row.getString("sha256"), row.getString("created_by"), row.getTimestamp("created_at").toInstant()), id)
                .stream().findFirst();
    }

    public boolean exists(String id) {
        if (id == null || id.isBlank()) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM question_image_asset WHERE id=?", Integer.class, id);
        return count != null && count == 1;
    }
}
