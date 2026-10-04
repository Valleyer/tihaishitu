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
    void dependencyReadinessUsesEffectiveMasteryAndLeavesLegacyScopeOnly() {
        String learner = learner();
        String k1 = knowledge("K1"), k2 = knowledge("K2"), k3 = knowledge("K3");
        String book = book(List.of(k1, k2, k3));
        question(2, relation(k1, "core"));
        question(2, relation(k2, "core"), relation(k1, "auxiliary"));
        question(2, relation(k3, "core"), relation(k2, "core"));
        AdaptiveStudyPlanner planner = new AdaptiveStudyPlanner(pool, states);
        Set<String> allowed = Set.of(k1, k2, k3);

        var initial = planner.planAt(learner, Set.of(book), List.of(), false, 1, NOW);
        assertThat(initial.readyKnowledgePointIds()).isEmpty();
        assertThat(initial.targetKnowledgePointIds()).containsExactly(k1);
        assertThat(pool.adaptivePlayableKnowledgePointIds(allowed, Set.of())).containsExactly(k1);

        states.save(learner, k1, state(80, 365, 3, NOW));
        var ready = planner.planAt(learner, Set.of(book), List.of(), false, 2, NOW);
        assertThat(ready.readyKnowledgePointIds()).containsExactly(k1);
        assertThat(pool.adaptivePlayableKnowledgePointIds(allowed, Set.of(k1)))
                .containsExactlyInAnyOrder(k1, k2);

        states.save(learner, k1, state(80, 1, 3, NOW.minusSeconds(2 * 86400L)));
        var forgotten = planner.planAt(learner, Set.of(book), List.of(), false, 1, NOW);
        assertThat(forgotten.readyKnowledgePointIds()).isEmpty();
        assertThat(pool.adaptivePlayableKnowledgePointIds(allowed, Set.of())).containsExactly(k1);
        assertThat(pool.playableKnowledgePointIds(allowed)).containsExactlyInAnyOrder(k1, k2, k3);
    }

    @Test
    void autoPriorityAndManualFocusComposeWithoutReplacingMasteryOrder() {
        String learner = learner();
        String weak = knowledge("weak"), learning = knowledge("learning"), fresh = knowledge("new");
        String ready = knowledge("ready"), proficient = knowledge("proficient");
        String book = book(List.of(weak, learning, fresh, ready, proficient));
        for (String point : List.of(weak, learning, fresh, ready, proficient))
            question(2, relation(point, "core"));
        states.save(learner, weak, state(30, 365, 5, NOW));
        states.save(learner, learning, state(60, 365, 4, NOW));
        states.save(learner, ready, state(75, 365, 4, NOW));
        states.save(learner, proficient, state(90, 365, 5, NOW));
        AdaptiveStudyPlanner planner = new AdaptiveStudyPlanner(pool, states);

        var automatic = planner.planAt(learner, Set.of(book), List.of(), false, 5, NOW);
        assertThat(automatic.targetKnowledgePointIds())
                .containsExactly(weak, learning, fresh, ready, proficient);

        var manual = planner.planAt(learner, Set.of(book), List.of(ready), true, 5, NOW);
        assertThat(manual.targetKnowledgePointIds())
                .containsExactly(ready, weak, learning, fresh, proficient);
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
