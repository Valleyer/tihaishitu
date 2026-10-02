package cn.tihaishitu;

import cn.tihaishitu.knowledge.Math1KnowledgeSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class Math1KnowledgeSeedIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Math1KnowledgeSeed seed;

    @Test
    void importsExactOfficialCountsAndAliasSearchData() {
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE subject_name = '数学一'"))
                .isEqualTo(469);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE section_name = '高等数学'"))
                .isEqualTo(198);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE section_name = '线性代数'"))
                .isEqualTo(136);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE section_name = '概率论与数理统计'"))
                .isEqualTo(135);

        String canonical = jdbc.queryForObject("""
                SELECT k.code
                  FROM global_knowledge_point k
                  JOIN knowledge_alias a ON a.knowledge_point_id = k.id
                 WHERE a.alias = '挖洞高斯'
                """, String.class);
        assertThat(canonical).isEqualTo("M1-H06-035");
    }

    @Test
    void repeatedSeedKeepsStableIdentityAndDoesNotDuplicate() throws Exception {
        String before = jdbc.queryForObject(
                "SELECT id FROM global_knowledge_point WHERE code = 'M1-H06-035'", String.class);
        seed.run(new DefaultApplicationArguments(new String[0]));
        String after = jdbc.queryForObject(
                "SELECT id FROM global_knowledge_point WHERE code = 'M1-H06-035'", String.class);

        assertThat(after).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE subject_name = '数学一'"))
                .isEqualTo(469);
        assertThat(count("SELECT COUNT(*) FROM knowledge_alias WHERE knowledge_point_id = ?", before))
                .isEqualTo(4);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
