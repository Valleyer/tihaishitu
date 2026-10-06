package cn.tihaishitu;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

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
        assertThat(tableExists("question_bank_chapter")).isTrue();
        assertThat(tableExists("question_bank_knowledge")).isTrue();
    }

    @Test
    void v6UpgradesExistingV5RelationsWithoutChangingResourceIdentity() {
        String url = "jdbc:h2:mem:v5-upgrade-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "")
                .target(MigrationVersion.fromVersion("5")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String bankId = UUID.randomUUID().toString();
        String knowledgeId = UUID.randomUUID().toString();
        String questionId = UUID.randomUUID().toString();
        old.update("INSERT INTO question_bank(id, name, description, enabled, weight_value) VALUES (?, '旧书', '', TRUE, 1)", bankId);
        old.update("""
                INSERT INTO global_knowledge_point(
                    id, code, name, subject_name, section_name, chapter_name, default_role,
                    status, description, explanation, sort_order)
                VALUES (?, 'UPGRADE-KP', '旧知识点', '测试', '测试', '测试', 'core', 'active', '', '', 0)
                """, knowledgeId);
        old.update("""
                INSERT INTO question_resource(
                    id, subject_name, source_type, question_type, presentation_type, grading_mode,
                    content_markdown, standard_answer_json, analysis_markdown, difficulty, status)
                VALUES (?, '测试', 'custom', 'true_false', 'true_false', 'auto', '旧题', 'true', '', 1, 'published')
                """, questionId);
        old.update("INSERT INTO question_resource_knowledge(question_id, knowledge_point_id, relation_role, sort_order) VALUES (?, ?, 'core', 0)", questionId, knowledgeId);
        old.update("INSERT INTO question_bank_item(bank_id, question_id, sort_order) VALUES (?, ?, 0)", bankId, questionId);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(old.queryForObject("SELECT COUNT(*) FROM question_bank WHERE id = ?", Integer.class, bankId)).isEqualTo(1);
        assertThat(old.queryForObject("SELECT COUNT(*) FROM question_resource WHERE id = ?", Integer.class, questionId)).isEqualTo(1);
        assertThat(old.queryForObject("SELECT COUNT(*) FROM global_knowledge_point WHERE id = ?", Integer.class, knowledgeId)).isEqualTo(1);
        assertThat(old.queryForObject("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ? AND question_id = ?", Integer.class, bankId, questionId)).isEqualTo(1);
        assertThat(columnExists(old, "question_bank_item", "chapter_id")).isFalse();
    }

    @Test
    void v18BackfillsAnyHistoricalFormalFailureEvenWhenTheLatestAnswerWasCorrect() {
        String url = "jdbc:h2:mem:v17-wrong-backfill-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "")
                .target(MigrationVersion.fromVersion("17")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String learner = UUID.randomUUID().toString(), point = UUID.randomUUID().toString();
        String question = UUID.randomUUID().toString(), wrongAttempt = UUID.randomUUID().toString();
        old.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,'回填','x','active',1)",
                learner, "backfill-" + learner);
        old.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, "BACKFILL-" + point, "回填知识点");
        old.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','题','true','解析',2,'published',1)
                """, question);
        old.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                question, point);
        old.update("""
                INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at)
                VALUES (?,?,?,'{}','true','graded','auto','automatic','wrong',?,'normal',2,TIMESTAMP '2026-01-01 08:00:00')
                """, wrongAttempt, learner, question, point);
        old.update("""
                INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at)
                VALUES (?,?,?,'{}','true','graded','auto','automatic','correct',?,'normal',2,TIMESTAMP '2026-01-02 08:00:00')
                """, UUID.randomUUID().toString(), learner, question, point);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(old.queryForObject("SELECT status FROM learner_wrong_question WHERE learner_id=? AND question_id=?",
                String.class, learner, question)).isEqualTo("active");
        assertThat(old.queryForObject("SELECT last_wrong_attempt_id FROM learner_wrong_question WHERE learner_id=? AND question_id=?",
                String.class, learner, question)).isEqualTo(wrongAttempt);
    }

    private boolean tableExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?",
                Integer.class,
                name.toLowerCase());
        return count != null && count > 0;
    }

    private boolean columnExists(JdbcTemplate template, String table, String column) {
        Integer count = template.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)
                """, Integer.class, table, column);
        return count != null && count > 0;
    }
}
