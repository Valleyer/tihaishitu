package cn.tihaishitu;

import cn.tihaishitu.manage.KnowledgeManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:merge-practice;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class KnowledgeMergePracticeIntegrationTest {
    @Autowired JdbcTemplate jdbc; @Autowired KnowledgeManagementService service;

    @Test void mergeCanonicalizesPracticeScopeAttemptsAndDiagnosisUnderLearnerLock() {
        String source=point("MERGE-P-S"),target=point("MERGE-P-T"),learner=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",learner,"merge-"+learner,"合并");
        jdbc.update("INSERT INTO learner_study_profile(learner_id,pace,difficulty,focus_mode) VALUES (?,'normal','standard','auto')",learner);
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,status,revision) VALUES (?,?,'knowledge_drill',?,'active',1)",session,learner,source);
        jdbc.update("INSERT INTO learner_practice_scope(session_id,knowledge_point_id) VALUES (?,?)",session,source);
        jdbc.update("INSERT INTO learner_practice_scope(session_id,knowledge_point_id) VALUES (?,?)",session,target);
        String question=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','true_false','true_false','auto','题','true','',2,'published',1)",question);
        String attempt=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode,target_knowledge_point_id,evidence_mode,question_difficulty) VALUES (?,NULL,?,NULL,?,?,'{}','true','graded','auto',?,'normal',2)",attempt,learner,session,question,source);
        String diagnosis=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_diagnosis_session(id,learner_id,world_id,practice_session_id,root_attempt_id,target_knowledge_point_id,status,has_unavailable_dependency,revision) VALUES (?,?,NULL,?,?,?,'resolved',FALSE,1)",diagnosis,learner,session,attempt,source);
        jdbc.update("INSERT INTO learner_diagnosis_dependency(diagnosis_id,knowledge_point_id,sort_order,status) VALUES (?,?,0,'passed')",diagnosis,source);

        service.merge(source,target,1,"专项知识点归并",learner);
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM learner_practice_session WHERE id=?",String.class,session)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_practice_scope WHERE session_id=?",Integer.class,session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT knowledge_point_id FROM learner_practice_scope WHERE session_id=?",String.class,session)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?",String.class,attempt)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM learner_diagnosis_session WHERE id=?",String.class,diagnosis)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=?",Integer.class,diagnosis)).isZero();
    }
    private String point(String code){String id=UUID.randomUUID().toString();jdbc.update(
            "INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',0,1)",id,code,code);return id;}
}
