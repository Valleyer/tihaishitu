package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:review-queue;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ReviewQueueIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired LearnerKnowledgeStateStore states;
    @Autowired ReviewQueueService reviews;

    @Test
    void derivesDeduplicatedSelectedBookQueueAndAdaptivePlayableState() {
        String learner = learner();
        String unstarted = knowledge("K1 未开始"), learning = knowledge("K2 学习中");
        String due = knowledge("K3 当前巩固"), upcoming = knowledge("K4 未来巩固");
        String firstBook = book(List.of(unstarted, learning, due, upcoming));
        String secondBook = book(List.of(upcoming));
        select(learner, firstBook);
        select(learner, secondBook);

        states.save(learner, learning, state(60, 365, NOW));
        states.save(learner, due, state(90, 10, NOW.minusSeconds(4 * 86_400L)));
        states.save(learner, upcoming, state(90, 10, NOW.minusSeconds(12 * 3_600L)));
        question(relation(due, "core"));
        question(relation(upcoming, "core"), relation(learning, "auxiliary"));
        int stateCount = count("learner_knowledge_state");
        int evidenceCount = count("learner_knowledge_evidence");

        ReviewQueueService.ReviewQueue queue = reviews.queueAt(learner, NOW);

        assertThat(queue.items()).extracting(ReviewQueueService.ReviewItem::knowledgePointId)
                .containsExactly(due, upcoming);
        assertThat(queue.items()).extracting(ReviewQueueService.ReviewItem::status)
                .containsExactly("due", "upcoming");
        assertThat(queue.items()).extracting(ReviewQueueService.ReviewItem::playable)
                .containsExactly(true, false);
        assertThat(queue.summary()).isEqualTo(new ReviewQueueService.ReviewSummary(1, 0, 1, 1));
        assertThat(count("learner_knowledge_state")).isEqualTo(stateCount);
        assertThat(count("learner_knowledge_evidence")).isEqualTo(evidenceCount);
    }

    private String learner() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, "review-" + id, "复习学习者");
        jdbc.update("INSERT INTO learner_study_profile(learner_id,pace,difficulty,focus_mode,revision) VALUES (?,'normal','standard','auto',1)", id);
        return id;
    }

    private String knowledge(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','分部','章节','core','active','','',0,1)
                """, id, "REVIEW-" + id, name);
        return id;
    }

    private String book(List<String> points) {
        String id = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'复习文集','',TRUE,1,1)", id);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, id);
        for (int index = 0; index < points.size(); index++)
            jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                    id, points.get(index), chapter, index);
        return id;
    }

    private void select(String learner, String book) {
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
    }

    private void question(Relation... relations) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto','题目','true','解析',3,'published',1)
                """, id);
        for (int index = 0; index < relations.length; index++)
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,?,?)",
                    id, relations[index].pointId(), relations[index].role(), index);
    }

    private static KnowledgeMasteryModel.State state(double mastery, double stability, Instant at) {
        return new KnowledgeMasteryModel.State(mastery, stability, 3, 1, 0, 0,
                "correct", at, at, "v1", 1);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Relation relation(String pointId, String role) { return new Relation(pointId, role); }
    private record Relation(String pointId, String role) {}
}
