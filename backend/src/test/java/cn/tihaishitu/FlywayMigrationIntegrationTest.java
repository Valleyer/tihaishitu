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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        insertTrueFalseOptions(old, questionId);

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
        insertTrueFalseOptions(old, question);
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

    @Test
    void v19BackfillsDistinctSourceIdentitiesAndKeepsBlankLegacyRows() {
        String url = "jdbc:h2:mem:v18-source-backfill-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("18")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String realOne = insertLegacyQuestion(old, "real_exam", "同名来源");
        String realTwo = insertLegacyQuestion(old, "real_exam", "同名来源");
        String mock = insertLegacyQuestion(old, "mock", "同名来源");
        String blank = insertLegacyQuestion(old, "custom", null);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(old.queryForObject("SELECT COUNT(*) FROM question_source", Integer.class)).isEqualTo(2);
        assertThat(old.queryForObject("SELECT source_id FROM question_resource WHERE id=?", String.class, realOne))
                .isEqualTo(old.queryForObject("SELECT source_id FROM question_resource WHERE id=?", String.class, realTwo));
        assertThat(old.queryForObject("SELECT source_id FROM question_resource WHERE id=?", String.class, mock))
                .isNotEqualTo(old.queryForObject("SELECT source_id FROM question_resource WHERE id=?", String.class, realOne));
        assertThat(old.queryForObject("SELECT source_id FROM question_resource WHERE id=?", String.class, blank)).isNull();
    }

    @Test
    void v20MigratesFormalAnswersAndPreservesRemedialCompatibility() {
        String url = "jdbc:h2:mem:v19-question-contract-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("19")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String objective = insertLegacyQuestion(old, "custom", "契约迁移");
        String solution = UUID.randomUUID().toString();
        old.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','solution','self_assessment','self_assessment','综合题',?,'旧解析',2,'published',1)
                """, solution, "\"旧参考答案\"");
        String child = UUID.randomUUID().toString();
        old.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,
                    parent_question_id,derivation_type,revision)
                VALUES (?,'测试','custom','solution','self_assessment','self_assessment','补救题',?,'补救解析',1,
                    'published',?,'remedial_step',1)
                """, child, "\"补救答案\"", solution);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(old.queryForObject("SELECT standard_answer_json FROM question_resource WHERE id=?", String.class, objective)).isNull();
        assertThat(old.queryForObject("SELECT standard_answer_json FROM question_resource WHERE id=?", String.class, solution)).isNull();
        assertThat(old.queryForObject("SELECT analysis_markdown FROM question_resource WHERE id=?", String.class, solution))
                .isEqualTo("## 参考答案\n\n旧参考答案\n\n## 解析\n\n旧解析");
        assertThat(old.queryForObject("SELECT standard_answer_json FROM question_resource WHERE id=?", String.class, child))
                .isEqualTo("\"补救答案\"");
    }

    @Test
    void v20RejectsInvalidPublishedObjectiveContractWithQuestionId() {
        String url = "jdbc:h2:mem:v19-invalid-contract-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("19")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String invalid = insertLegacyQuestion(old, "custom", "非法契约");
        old.update("DELETE FROM question_resource_option WHERE question_id=?", invalid);

        assertThatThrownBy(() -> Flyway.configure().dataSource(url, "sa", "").load().migrate())
                .satisfies(error -> assertThat(rootCause(error).getMessage()).contains(invalid));
    }

    @Test
    void v20UsesTheSameObjectiveLimitsAsTheFormalValidator() {
        // 7 个选项超出 Formal Validator 的 2–6 上限，migration 必须同样拒绝。
        String tooMany = migrationUrl("v19-too-many-options");
        JdbcTemplate tooManyDb = migrateToV19AndInsert(tooMany, "custom", "选项过多");
        for (int index = 0; index < 5; index++) {
            tooManyDb.update("""
                    INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order)
                    VALUES (?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(),
                    tooManyDb.queryForObject("SELECT id FROM question_resource WHERE source_name='选项过多'", String.class),
                    "E" + index, "补充", false, index + 2);
        }
        assertThatThrownBy(() -> Flyway.configure().dataSource(tooMany, "sa", "").load().migrate())
                .satisfies(error -> assertThat(rootCause(error).getMessage()).contains("2–6 个选项"));

        // 空选项值同样会被 Formal Validator 拒绝，migration 不能放行。
        String blankText = migrationUrl("v19-blank-option-text");
        JdbcTemplate blankTextDb = migrateToV19AndInsert(blankText, "custom", "空选项值");
        blankTextDb.update("UPDATE question_resource_option SET option_text='' WHERE question_id=?",
                blankTextDb.queryForObject("SELECT id FROM question_resource WHERE source_name='空选项值'", String.class));
        assertThatThrownBy(() -> Flyway.configure().dataSource(blankText, "sa", "").load().migrate())
                .satisfies(error -> assertThat(rootCause(error).getMessage()).contains("选项键和值不能为空"));
    }

    @Test
    void v20FailsWhenAPublishedSolutionHasNoAnalysisEvenAfterMergingTheLegacyAnswer() {
        String url = migrationUrl("v19-empty-solution-analysis");
        JdbcTemplate old = migrateToV19(url);
        String solution = UUID.randomUUID().toString();
        // 旧答案与旧解析都只有空白，合并后 analysis_markdown 仍然为空：必须 fail 而不是静默放过。
        old.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','solution','self_assessment','self_assessment','综合题','"   "','   ',2,'published',1)
                """, solution);

        assertThatThrownBy(() -> Flyway.configure().dataSource(url, "sa", "").load().migrate())
                .satisfies(error -> {
                    assertThat(rootCause(error).getMessage()).contains(solution);
                    assertThat(rootCause(error).getMessage()).contains("analysis_markdown");
                });
    }

    @Test
    void v20MergesTheLegacyAnswerSoAPublishedSolutionKeepsItsContent() {
        String url = migrationUrl("v19-merged-solution-analysis");
        JdbcTemplate old = migrateToV19(url);
        String solution = UUID.randomUUID().toString();
        old.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','solution','self_assessment','self_assessment','综合题',?,'',2,'published',1)
                """, solution, "\"只有旧答案\"");

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(old.queryForObject("SELECT analysis_markdown FROM question_resource WHERE id=?",
                String.class, solution)).isEqualTo("## 参考答案\n\n只有旧答案");
    }

    @Test
    void v21AddsDrawModeAndDrawReasonAndKeepsHistoricalAttemptsReadable() {
        String url = migrationUrl("v20-draw-mode");
        Flyway.configure().dataSource(url, "sa", "")
                .target(MigrationVersion.fromVersion("20")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String learner = UUID.randomUUID().toString(), point = UUID.randomUUID().toString();
        String question = UUID.randomUUID().toString(), attempt = UUID.randomUUID().toString();
        old.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,'旧作答','x','active',1)",
                learner, "draw-mode-" + learner);
        old.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, "DRAW-" + point, "画法知识点");
        old.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','旧题','true','解析',2,'published',1)
                """, question);
        insertTrueFalseOptions(old, question);
        old.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                question, point);
        old.update("""
                INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at)
                VALUES (?,?,?,'{}','true','graded','auto','automatic','correct',?,'normal',2,TIMESTAMP '2026-01-01 08:00:00')
                """, attempt, learner, question, point);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(columnExists(old, "study_attempt", "draw_mode")).isTrue();
        assertThat(columnExists(old, "study_attempt", "draw_reason")).isTrue();
        // 旧 Attempt 保持可读：不做高风险历史回填，两列仍然是 NULL。
        assertThat(old.queryForObject("SELECT COUNT(*) FROM study_attempt", Integer.class)).isEqualTo(1);
        assertThat(old.queryForObject("SELECT draw_mode FROM study_attempt WHERE id=?", String.class, attempt)).isNull();
        assertThat(old.queryForObject("SELECT draw_reason FROM study_attempt WHERE id=?", String.class, attempt)).isNull();
        // RANDOM 每日硬去重依赖 (learner_id, draw_mode, created_at, question_id) 索引。
        assertThat(indexExists(old, "study_attempt", "idx_attempt_learner_draw_mode")).isTrue();
    }

    private String migrationUrl(String name) {
        return "jdbc:h2:mem:" + name + "-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private JdbcTemplate migrateToV19(String url) {
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("19")).load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    private JdbcTemplate migrateToV19AndInsert(String url, String type, String name) {
        JdbcTemplate old = migrateToV19(url);
        insertLegacyQuestion(old, type, name);
        return old;
    }

    private String insertLegacyQuestion(JdbcTemplate template, String type, String name) {
        String id = UUID.randomUUID().toString();
        template.update("""
                INSERT INTO question_resource(id,subject_name,source_type,source_name,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试',?,?,'true_false','true_false','auto','题','true','解析',2,'published',1)
                """, id, type, name);
        insertTrueFalseOptions(template, id);
        return id;
    }

    private static void insertTrueFalseOptions(JdbcTemplate template, String questionId) {
        template.update("INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), questionId, "true", "正确", true, 0);
        template.update("INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), questionId, "false", "错误", false, 1);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null) result = result.getCause();
        return result;
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

    private boolean indexExists(JdbcTemplate template, String table, String index) {
        Integer count = template.queryForObject("""
                SELECT COUNT(*) FROM information_schema.indexes
                 WHERE LOWER(table_name) = LOWER(?) AND LOWER(index_name) = LOWER(?)
                """, Integer.class, table, index);
        return count != null && count > 0;
    }
}
