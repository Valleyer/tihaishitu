package cn.tihaishitu;

import cn.tihaishitu.manage.GlobalKnowledgeBatchImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:knowledge-batch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GlobalKnowledgeBatchImportIntegrationTest {
    @Autowired GlobalKnowledgeBatchImportService importer;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsAndUpdatesBookChapterKnowledgeAliasesAndMembershipWithoutChangingStableIds() throws Exception {
        String bookId = UUID.randomUUID().toString();
        String payload = payload(bookId, "知识点初版", "别名甲");
        var first = importer.importBatch(mapper.readTree(payload), null);
        String pointId = jdbc.queryForObject("SELECT id FROM global_knowledge_point WHERE code='TEST-K-001'", String.class);

        var second = importer.importBatch(mapper.readTree(payload(bookId, "知识点修订", "别名乙")), null);

        assertThat(first.createdKnowledgePoints()).isEqualTo(1);
        assertThat(second.updatedKnowledgePoints()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM global_knowledge_point WHERE code='TEST-K-001'", String.class))
                .isEqualTo(pointId);
        assertThat(jdbc.queryForObject("SELECT name FROM global_knowledge_point WHERE id=?", String.class, pointId))
                .isEqualTo("知识点修订");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id=? AND knowledge_point_id=?", Integer.class, bookId, pointId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT alias FROM knowledge_alias WHERE knowledge_point_id=?", String.class, pointId)).isEqualTo("别名乙");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource", Integer.class)).isZero();
    }

    private String payload(String bookId, String name, String alias) {
        return """
                {"schemaVersion":"global-knowledge-batch/v1","book":{"id":"%s","name":"测试文集","description":"","enabled":true},
                 "subject":"测试学科","chapters":[{"code":"ROOT","name":"总章","parentCode":null,"description":"","sortOrder":0}],
                 "knowledgePoints":[{"code":"TEST-K-001","name":"%s","section":"测试分科","chapter":"总章","chapterCode":"ROOT","defaultRole":"core","status":"active","description":"","explanation":"","sortOrder":0,"aliases":["%s"]}]}
                """.formatted(bookId, name, alias);
    }
}
