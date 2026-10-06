package cn.tihaishitu;

import cn.tihaishitu.manage.KnowledgeGuideManagementService;
import cn.tihaishitu.manage.RemedialQuestionManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learning-v6-management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearningArchitectureV6ManagementIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KnowledgeGuideManagementService guides;
    @Autowired RemedialQuestionManagementService remedial;

    @Test
    void guideAndRemedialBatchesRoundTripMarkdownLatexAndParentRelationship() throws Exception {
        String actor = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString();
        String parent = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'v6-admin','V6 管理员','x','active',1)", actor);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,'V6-GUIDE','导数','数学','微分','导数','core','active','定义','',0,1)
                """, point);
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学','custom','true_false','true_false','auto','父题','true','父题解析',2,'published',1)
                """, parent);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", parent, point);

        var guideResult = guides.importBatch(mapper.readTree("""
                {"schemaVersion":"knowledge-guide-batch/v1","guides":[{
                  "knowledgePointId":"%s","knowledgePointCode":"V6-GUIDE",
                  "contentMarkdown":"## 定义\\n当 $f'(x)=0$ 时检查极值。"
                }]}
                """.formatted(point)), actor);
        assertThat(guideResult.created()).isOne();
        var guideExport = guides.export(new KnowledgeGuideManagementService.ExportRequest(List.of(point), null, null));
        assertThat(guideExport.get("schemaVersion")).isEqualTo("knowledge-guide-generation/v1");
        assertThat(jdbc.queryForObject("SELECT content_markdown FROM knowledge_point_guide WHERE knowledge_point_id=?",
                String.class, point)).contains("$f'(x)=0$");

        String steps = """
                {"schemaVersion":"remedial-question-batch/v1","parents":[{"parentQuestionId":"%s","steps":[
                  {"stepOrder":1,"trainingGoal":"识别条件","questionType":"solution","presentationType":"self_assessment","gradingMode":"self_assessment","contentMarkdown":"步骤 $1$","options":[],"standardAnswer":"参考解答一","analysisMarkdown":"解析一"},
                  {"stepOrder":2,"trainingGoal":"代入公式","questionType":"solution","presentationType":"self_assessment","gradingMode":"self_assessment","contentMarkdown":"步骤 $2$","options":[],"standardAnswer":"参考解答二","analysisMarkdown":"解析二"},
                  {"stepOrder":3,"trainingGoal":"完成计算","questionType":"solution","presentationType":"self_assessment","gradingMode":"self_assessment","contentMarkdown":"步骤 $3$","options":[],"standardAnswer":"参考解答三","analysisMarkdown":"解析三"}
                ]}]}
                """.formatted(parent);
        var remedialResult = remedial.importBatch(mapper.readTree(steps), actor);
        assertThat(remedialResult.created()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource WHERE parent_question_id=? AND derivation_type='remedial_step'",
                Integer.class, parent)).isEqualTo(3);
        var remedialExport = remedial.export(new RemedialQuestionManagementService.ExportRequest(
                List.of(parent), null, null, null, null));
        assertThat(remedialExport.get("schemaVersion")).isEqualTo("remedial-question-generation/v1");
    }
}
