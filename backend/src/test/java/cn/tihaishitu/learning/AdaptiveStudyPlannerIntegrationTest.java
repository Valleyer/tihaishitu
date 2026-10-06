package cn.tihaishitu.learning;

import cn.tihaishitu.game.KnowledgeQuestionPoolStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:adaptive-planner;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AdaptiveStudyPlannerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeQuestionPoolStore pool;
    @Autowired LearnerKnowledgeStateStore states;

    @Test
    void playableScopeNoLongerDependsOnDependencyReadinessOrMastery() {
        String learner = learner();
        String k1 = knowledge("K1"), k2 = knowledge("K2"), k3 = knowledge("K3");
        String book = book(List.of(k1, k2, k3));
        question(2, relation(k1, "core"));
        question(2, relation(k2, "core"), relation(k1, "auxiliary"));
        question(2, relation(k3, "core"), relation(k2, "core"));
        AdaptiveStudyPlanner planner = new AdaptiveStudyPlanner(pool, states);
        Set<String> allowed = Set.of(k1, k2, k3);

        // 最新规则：core + auxiliary 都算覆盖，前置知识点未 ready 也不再阻止登记为可练。
        assertThat(pool.playableKnowledgePointIds(allowed)).containsExactlyInAnyOrder(k1, k2, k3);

        // 全部未开始：三个知识点都可作为目标，随机计划不能因为 mastery=0 失败。
        var initial = planner.planAt(learner, Set.of(book), List.of(), false, 3, NOW);
        assertThat(initial.targetKnowledgePointIds()).containsExactlyInAnyOrder(k1, k2, k3);

        // 即使 K1 达到高掌握度，可练集合也不改变；忘记（effective 下降）同样不改变可练集合。
        states.save(learner, k1, state(80, 365, 3, NOW));
        assertThat(pool.playableKnowledgePointIds(allowed)).containsExactlyInAnyOrder(k1, k2, k3);
        states.save(learner, k1, state(80, 1, 3, NOW.minusSeconds(2 * 86400L)));
        assertThat(pool.playableKnowledgePointIds(allowed)).containsExactlyInAnyOrder(k1, k2, k3);
        assertThat(planner.planAt(learner, Set.of(book), List.of(), false, 3, NOW).targetKnowledgePointIds())
                .containsExactlyInAnyOrder(k1, k2, k3);
    }

    @Test
    void manualFocusPrioritizesFocusedTargetsWithoutDroppingOthers() {
        String learner = learner();
        String weak = knowledge("weak"), review = knowledge("review"), fresh = knowledge("new");
        String ready = knowledge("ready"), proficient = knowledge("proficient");
        String book = book(List.of(weak, review, fresh, ready, proficient));
        for (String point : List.of(weak, review, fresh, ready, proficient))
            question(2, relation(point, "core"));
        states.save(learner, weak, state(60, 365, 4, NOW));
        states.save(learner, review, state(90, 10, 4, NOW.minusSeconds(75 * 3_600L)));
        states.save(learner, ready, state(75, 365, 4, NOW));
        states.save(learner, proficient, state(90, 365, 5, NOW));
        AdaptiveStudyPlanner planner = new AdaptiveStudyPlanner(pool, states);

        // 自动模式下候选只按随机池选取，不再使用 mastery / review 排序。
        var automatic = planner.planAt(learner, Set.of(book), List.of(), false, 5, NOW);
        assertThat(automatic.targetKnowledgePointIds())
                .containsExactlyInAnyOrder(weak, review, fresh, ready, proficient);

        // 手动 Focus 只做优先级前置：被聚焦的知识点排在最前，其余按随机顺序补齐。
        var manual = planner.planAt(learner, Set.of(book), List.of(fresh, review), true, 5, NOW);
        assertThat(manual.targetKnowledgePointIds()).hasSize(5);
        assertThat(manual.targetKnowledgePointIds().subList(0, 2)).containsExactly(fresh, review);
        assertThat(manual.targetKnowledgePointIds())
                .containsExactlyInAnyOrder(weak, review, fresh, ready, proficient);
    }

    private String learner() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, "adaptive-" + id, "自适应学习者");
        return id;
    }

    private String knowledge(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','分部','章节','core','active','','',0,1)
                """, id, "ADAPT-" + id, name);
        return id;
    }

    private String book(List<String> points) {
        String id = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'自适应文集','',TRUE,1,1)", id);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, id);
        for (int index = 0; index < points.size(); index++)
            jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                    id, points.get(index), chapter, index);
        return id;
    }

    private String question(int difficulty, Relation... relations) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','题目','true','解析',?,'published',1)
                """, id, difficulty);
        for (int index = 0; index < relations.length; index++)
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,?,?)",
                    id, relations[index].pointId(), relations[index].role(), index);
        return id;
    }

    private static KnowledgeMasteryModel.State state(double mastery, double stability, int difficulty, Instant at) {
        return new KnowledgeMasteryModel.State(mastery, stability, difficulty, 1, 0, 0, "correct", at, at, "v1", 1);
    }

    private static Relation relation(String pointId, String role) { return new Relation(pointId, role); }
    private record Relation(String pointId, String role) {}
}
