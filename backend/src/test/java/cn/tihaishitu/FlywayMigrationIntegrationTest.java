package cn.tihaishitu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FlywayMigrationIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesVersionedLegacyAndGlobalSchemas() {
        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(2);

        assertThat(tableExists("question_bank")).isTrue();
        assertThat(tableExists("global_knowledge_point")).isTrue();
        assertThat(tableExists("question_resource")).isTrue();
        assertThat(tableExists("app_user")).isTrue();
    }

    private boolean tableExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?",
                Integer.class,
                name.toLowerCase());
        return count != null && count > 0;
    }
}
