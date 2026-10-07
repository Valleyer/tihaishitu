package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.manage.ManageUserDeletionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:user-deletion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class UserDeletionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ManageUserDeletionService deletion;

    @Test void deleteRemovesLearnerDataAndAnonymizesSharedResources() throws Exception {
        Cookie admin = account("delete-admin", "ADMIN");
        String adminId = id("delete-admin");
        account("delete-target");
        String targetId = id("delete-target");
        String legacyId = UUID.randomUUID().toString();
        String bookId = UUID.randomUUID().toString();
        String pointId = knowledge("DELETE-SOURCE");
        String targetPointId = knowledge("DELETE-TARGET");
        String questionId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String practiceId = UUID.randomUUID().toString();
        String diagnosisId = UUID.randomUUID().toString();

        jdbc.update("INSERT INTO app_user(id,username,display_name,password_hash,status,revision) VALUES (?,'delete-target','旧账号','x','active',1)", legacyId);
        jdbc.update("INSERT INTO app_user_role(user_id,role_name) VALUES (?,'REVIEWER')", legacyId);
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value) VALUES (?,'保留文集','',TRUE,100)", bookId);
        jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,source_name,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,created_by,updated_by,reviewed_by,revision) VALUES (?,'测试','custom','删除测试','true_false','true_false','auto','题目','true','解析',2,'published',?,?,?,1)",
                questionId, targetId, legacyId, targetId);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order,created_by) VALUES (?,?,'core',0,?)", questionId, pointId, legacyId);
        jdbc.update("INSERT INTO question_bank_item(bank_id,question_id,sort_order) VALUES (?,?,0)", bookId, questionId);
        jdbc.update("INSERT INTO study_attempt(id,game_id,learner_id,world_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode) VALUES (?,NULL,?,'ancient-official',?,'{}','true','graded','auto')", attemptId, targetId, questionId);
        jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,source_question_id,status) VALUES (?,?,'knowledge_drill',?,?, 'active')", practiceId, targetId, pointId, questionId);
        jdbc.update("INSERT INTO learner_practice_scope(session_id,knowledge_point_id) VALUES (?,?)", practiceId, pointId);
        jdbc.update("INSERT INTO learner_diagnosis_session(id,learner_id,world_id,root_attempt_id,target_knowledge_point_id,status,practice_session_id) VALUES (?,?,NULL,?,?, 'dependency_probe',?)", diagnosisId, targetId, attemptId, pointId, practiceId);
        jdbc.update("INSERT INTO learner_diagnosis_dependency(diagnosis_id,knowledge_point_id,sort_order,status) VALUES (?,?,0,'pending')", diagnosisId, targetPointId);
        jdbc.update("UPDATE study_attempt SET diagnosis_session_id=?,practice_session_id=? WHERE id=?", diagnosisId, practiceId, attemptId);
        jdbc.update("UPDATE learner_practice_session SET current_attempt_id=? WHERE id=?", attemptId, practiceId);
        jdbc.update("INSERT INTO answer_record(id,game_id,learner_id,world_id,attempt_id,question_id,submitted_answer_json,correct,grading_source) VALUES (?,NULL,?,'ancient-official',?,?, 'false',FALSE,'automatic')", UUID.randomUUID().toString(), targetId, attemptId, questionId);
        jdbc.update("INSERT INTO question_report(id,learner_id,question_id,attempt_id,reason,status) VALUES (?,?,?,?, 'other','open')", UUID.randomUUID().toString(), targetId, questionId, attemptId);
        jdbc.update("INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,target_difficulty,evidence_count,correct_streak,wrong_streak,last_outcome,model_version,revision) VALUES (?,?,40,2,2,1,0,1,'wrong','v1',1)", targetId, pointId);
        jdbc.update("INSERT INTO learner_knowledge_evidence(id,learner_id,knowledge_point_id,attempt_id,question_id,world_id,outcome,grading_source,evidence_mode,question_difficulty,quality,learning_rate,effective_mastery_before,mastery_after,stability_before,stability_after,target_difficulty_before,target_difficulty_after,model_version,occurred_at) VALUES (?,?,?,?,?,'ancient-official','wrong','automatic','target',2,0,0.1,50,40,2,2,2,2,'v1',CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), targetId, pointId, attemptId, questionId);
        jdbc.update("INSERT INTO learner_world_state(learner_id,world_id,state_json,revision) VALUES (?,'ancient-official','{}',1)", targetId);
        jdbc.update("INSERT INTO content_audit_log(id,actor_user_id,actor_learner_id,action_name,entity_type,entity_id,metadata_json) VALUES (?,?,?,'TEST_ACTION','question',?,'{}')", UUID.randomUUID().toString(), legacyId, targetId, questionId);
        jdbc.update("INSERT INTO knowledge_merge_history(id,source_knowledge_id,target_knowledge_id,actor_user_id,actor_learner_id,migrated_relation_count,collapsed_relation_count,reason) VALUES (?,?,?,?,?,0,0,'测试')", UUID.randomUUID().toString(), pointId, targetPointId, legacyId, targetId);

        mvc.perform(delete("/api/v1/manage/users/{id}", targetId).with(csrf()).cookie(admin))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM learner_account WHERE id=?", targetId)).isZero();
        for (String table : new String[]{"learner_account_role", "learner_session",
                "learner_study_profile", "learner_selected_book", "learner_focus_knowledge", "learner_world_state",
                "study_attempt", "answer_record", "learner_knowledge_state", "learner_knowledge_evidence",
                "learner_practice_session", "learner_diagnosis_session"}) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE learner_id=?", targetId)).isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM learner_practice_scope WHERE session_id=?", practiceId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=?", diagnosisId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM question_report WHERE learner_id=?", targetId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE id=?", legacyId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id=?", questionId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE id IN (?,?)", pointId, targetPointId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM question_bank WHERE id=?", bookId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id=? AND created_by IS NULL AND updated_by IS NULL AND reviewed_by IS NULL", questionId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM question_resource_knowledge WHERE question_id=? AND created_by IS NULL", questionId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM content_audit_log WHERE entity_id=? AND actor_user_id IS NULL AND actor_learner_id IS NULL", questionId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM knowledge_merge_history WHERE source_knowledge_id=? AND actor_user_id IS NULL AND actor_learner_id IS NULL", pointId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM content_audit_log WHERE actor_learner_id=? AND action_name='USER_DELETED' AND entity_id=?", adminId, targetId)).isOne();
    }

    @Test void deleteGuardsCurrentAccountAndLastActiveAdmin() throws Exception {
        Cookie admin = account("guard-admin", "ADMIN");
        String adminId = id("guard-admin");
        mvc.perform(delete("/api/v1/manage/users/{id}", adminId).with(csrf()).cookie(admin))
                .andExpect(status().isBadRequest());
        account("guard-actor");
        String actorId = id("guard-actor");
        jdbc.update("DELETE FROM learner_account_role WHERE role_name='ADMIN' AND learner_id<>?", adminId);
        assertThatThrownBy(() -> deletion.delete(adminId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能删除最后一个有效管理员");
        assertThat(count("SELECT COUNT(*) FROM learner_account WHERE id=?", adminId)).isOne();
    }

    private Cookie account(String username, String... roles) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"password-123\"}"
                                .formatted(username, username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String id = id(username);
        for (String role : roles) jdbc.update("INSERT INTO learner_account_role(learner_id,role_name) VALUES (?,?)", id, role);
        return cookie;
    }

    private String id(String username) {
        return jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class, username);
    }

    private String knowledge(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,?,?,?,?,?,?,?,0,1)",
                id, code + "-" + id.substring(0, 8), code, "测试", "节", "章", "core", "active", "", "");
        return id;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
