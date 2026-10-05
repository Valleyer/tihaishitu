package cn.tihaishitu;

import cn.tihaishitu.learning.AdaptiveStudyPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:world-random-target;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class WorldRandomTargetPlannerIntegrationTest {
    @Autowired JdbcTemplate jdbc; @Autowired AdaptiveStudyPlanner planner;

    @Test void unstartedWeakAndProficientPlayableKnowledgeAreAllRandomCandidates() {
        String learner=UUID.randomUUID().toString(),book=UUID.randomUUID().toString(),chapter=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",learner,"random-"+learner,"随机");
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'随机文集','',TRUE,1,1)",book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)",chapter,book);
        String unstarted=point(book,chapter,"RANDOM-U",0);
        String weak=point(book,chapter,"RANDOM-W",1);
        String proficient=point(book,chapter,"RANDOM-P",2);
        state(learner,weak,15,1);
        state(learner,proficient,100,365);

        var plan=planner.randomPlan(learner,Set.of(book),3);
        assertThat(plan.targetKnowledgePointIds()).containsExactlyInAnyOrder(unstarted,weak,proficient);
    }

    private String point(String book,String chapter,String code,int order){
        String id=UUID.randomUUID().toString(),question=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',?,1)",id,code,code,order);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",book,id,chapter,order);
        jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)",question,code);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",question,id);
        return id;
    }
    private void state(String learner,String point,double mastery,double stability){
        jdbc.update("INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,target_difficulty,evidence_count,correct_streak,wrong_streak,last_outcome,last_evidence_at,last_correct_at,model_version,revision) VALUES (?,?,?,?,2,1,1,0,'correct',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1',1)",learner,point,mastery,stability);
    }
}
