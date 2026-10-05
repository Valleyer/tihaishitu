package cn.tihaishitu;

import cn.tihaishitu.manage.QuestionManagementStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:question-bulk-delete;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class QuestionBulkDeleteIntegrationTest {
    @Autowired QuestionManagementStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deletesChildrenBeforeParentsNullsEndedPracticeAndKeepsAttemptHistory() {
        String actor=learner("bulk-actor"), point=knowledge(), parent=question(null), child=question(parent);
        String session=practice(actor,point,child,"ended");
        String attempt=attempt(actor,session,child,point);

        assertThat(store.bulkDelete(new LinkedHashSet<>(java.util.List.of(parent,child)),actor)).isEqualTo(2);

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id IN (?,?)",parent,child)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_question_id FROM learner_practice_session WHERE id=?",String.class,session)).isNull();
        assertThat(count("SELECT COUNT(*) FROM study_attempt WHERE id=? AND question_id=?",attempt,child)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM content_audit_log WHERE action_name='QUESTION_BULK_DELETED'")).isEqualTo(1);
    }

    @Test
    void activeWrongPracticeBlocksWholeBatch() {
        String actor=learner("bulk-active"), point=knowledge(), question=question(null);
        practice(actor,point,question,"active");
        assertThatThrownBy(() -> store.bulkDelete(java.util.Set.of(question),actor))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("正在被错题练习使用");
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id=?",question)).isEqualTo(1);
    }

    private String learner(String username){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",id,username,username);return id;}
    private String knowledge(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',0,1)",id,"BULK-"+id,"批量知识");return id;}
    private String question(String parent){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,parent_question_id,revision) VALUES (?,'测试','custom','true_false','true_false','auto','题干','true','解析',1,'draft',?,1)",id,parent);return id;}
    private String practice(String learner,String point,String question,String status){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,source_question_id,status,revision) VALUES (?,?,'wrong_review',?,?,?,1)",id,learner,point,question,status);return id;}
    private String attempt(String learner,String session,String question,String point){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO study_attempt(id,learner_id,practice_session_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,question_difficulty,answered_at) VALUES (?,?,?,?,'{}','true','graded','auto','automatic','wrong',?,'normal',1,CURRENT_TIMESTAMP)",id,learner,session,question,point);return id;}
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
}
