package cn.tihaishitu.manage;

import cn.tihaishitu.catalog.QuestionContractValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class RemedialQuestionManagementService {
    public record ExportRequest(List<String> questionIds,String bookId,String chapterId,String knowledgePointId,Integer examYear){}
    public record StepInput(Integer stepOrder,String trainingGoal,String questionType,String presentationType,
                            String gradingMode,String contentMarkdown,List<OptionInput> options,
                            JsonNode standardAnswer,String analysisMarkdown){}
    public record OptionInput(String key,String text,Boolean correct,Integer sortOrder){}
    public record ParentInput(String parentQuestionId,List<StepInput> steps){}
    public record ImportRequest(String schemaVersion,List<ParentInput> parents){}
    public record ImportResult(String schemaVersion,int parentCount,int created,int updated,int archived){}

    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final KnowledgeManagementStore knowledge;
    public RemedialQuestionManagementService(JdbcTemplate jdbc,ObjectMapper mapper,KnowledgeManagementStore knowledge){this.jdbc=jdbc;this.mapper=mapper;this.knowledge=knowledge;}

    public Map<String,Object> export(ExportRequest request){
        List<Object> args=new ArrayList<>(); List<String> where=new ArrayList<>();
        where.add("q.parent_question_id IS NULL"); where.add("q.status='published'");
        where.add("q.question_type IN ('single_choice','multiple_choice','true_false','solution')");
        if(request!=null&&request.questionIds()!=null&&!request.questionIds().isEmpty()){
            where.add("q.id IN ("+marks(request.questionIds().size())+")");args.addAll(request.questionIds());
        }
        if(request!=null&&value(request.knowledgePointId())!=null){where.add("qk.knowledge_point_id=?");args.add(request.knowledgePointId());}
        if(request!=null&&value(request.bookId())!=null){where.add("bk.bank_id=?");args.add(request.bookId());}
        if(request!=null&&value(request.chapterId())!=null){where.add("bk.chapter_id=?");args.add(request.chapterId());}
        if(request!=null&&request.examYear()!=null){where.add("q.exam_year=?");args.add(request.examYear());}
        List<Map<String,Object>> questions=jdbc.query("""
                SELECT DISTINCT q.id,COALESCE(s.display_name,q.source_name) source_name,q.exam_year,q.question_number,q.content_markdown,
                       q.standard_answer_json,q.analysis_markdown
                  FROM question_resource q
                  LEFT JOIN question_source s ON s.id=q.source_id
                  LEFT JOIN question_resource_knowledge qk ON qk.question_id=q.id
                  LEFT JOIN question_bank_knowledge bk ON bk.knowledge_point_id=qk.knowledge_point_id
                 WHERE %s ORDER BY q.exam_year,q.question_number,q.id
                """.formatted(String.join(" AND ",where)),(rs,row)->{
            Map<String,Object> item=new LinkedHashMap<>();item.put("parentQuestionId",rs.getString("id"));
            item.put("sourceName",rs.getString("source_name"));item.put("examYear",rs.getObject("exam_year"));
            item.put("questionNumber",rs.getString("question_number"));item.put("contentMarkdown",rs.getString("content_markdown"));
            item.put("standardAnswer",jsonNode(rs.getString("standard_answer_json")));item.put("analysisMarkdown",rs.getString("analysis_markdown"));
            item.put("knowledgePoints",jdbc.query("SELECT k.id,k.code,k.name FROM question_resource_knowledge r JOIN global_knowledge_point k ON k.id=r.knowledge_point_id WHERE r.question_id=? ORDER BY r.sort_order",
                    (kp,i)->Map.of("id",kp.getString(1),"code",kp.getString(2),"name",kp.getString(3)),rs.getString("id")));
            return item;
        },args.toArray());
        return Map.of("schemaVersion","remedial-question-generation/v1","generationInstructions",Map.of(
                "language","zh-CN","stepCount","3-5","goal","将真题求解过程拆为最基础、低难度、递进式子题","markdown",true,"latex",true),"questions",questions);
    }

    @Transactional
    public ImportResult importBatch(JsonNode document,String actorId){
        ImportRequest request;try{request=mapper.treeToValue(document,ImportRequest.class);}catch(JsonProcessingException e){throw bad("子题批次 JSON 字段类型不正确。");}
        if(request==null||!"remedial-question-batch/v1".equals(request.schemaVersion()))throw bad("schemaVersion 必须为 remedial-question-batch/v1。");
        if(request.parents()==null||request.parents().isEmpty())throw bad("至少需要一道父题。");
        int created=0,updated=0,archived=0;
        for(ParentInput parent:request.parents()){
            if(parent==null||value(parent.parentQuestionId())==null||!formalParent(parent.parentQuestionId()))throw bad("父题不存在或不是已发布正式题。");
            if(parent.steps()==null||parent.steps().size()<3||parent.steps().size()>5)throw bad("每道父题必须提供 3–5 个子题步骤。");
            Set<Integer> orders=new HashSet<>();
            for(StepInput step:parent.steps()){
                if(step==null||step.stepOrder()==null||step.stepOrder()<1||!orders.add(step.stepOrder())||value(step.trainingGoal())==null||value(step.contentMarkdown())==null||value(step.analysisMarkdown())==null)throw bad("子题步骤字段不完整或顺序重复。");
                validateStep(step);
                String existing=jdbc.query("SELECT id FROM question_resource WHERE parent_question_id=? AND derivation_type='remedial_step' AND derivation_order=?",
                        (rs,row)->rs.getString(1),parent.parentQuestionId(),step.stepOrder()).stream().findFirst().orElse(null);
                String id=existing==null?UUID.randomUUID().toString():existing;
                String subject=jdbc.queryForObject("SELECT subject_name FROM question_resource WHERE id=?",String.class,parent.parentQuestionId());
                if(existing==null){jdbc.update("""
                    INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,
                    content_markdown,standard_answer_json,analysis_markdown,difficulty,status,parent_question_id,derivation_type,
                    derivation_order,training_goal,created_by,updated_by,revision)
                    VALUES (?,?,'custom',?,?,?,?,?,?,1,'published',?,'remedial_step',?,?,?,?,1)
                    """,id,subject,step.questionType(),step.presentationType(),step.gradingMode(),step.contentMarkdown().trim(),json(step.standardAnswer()),step.analysisMarkdown().trim(),parent.parentQuestionId(),step.stepOrder(),step.trainingGoal().trim(),actorId,actorId);created++;}
                else{jdbc.update("""
                    UPDATE question_resource SET question_type=?,presentation_type=?,grading_mode=?,content_markdown=?,
                    standard_answer_json=?,analysis_markdown=?,status='published',training_goal=?,updated_by=?,revision=revision+1,
                    updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """,step.questionType(),step.presentationType(),step.gradingMode(),step.contentMarkdown().trim(),json(step.standardAnswer()),step.analysisMarkdown().trim(),step.trainingGoal().trim(),actorId,id);updated++;}
                jdbc.update("DELETE FROM question_resource_option WHERE question_id=?",id);
                int index=0;for(OptionInput option:safe(step.options()))jdbc.update("INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),id,option.key().trim(),option.text().trim(),Boolean.TRUE.equals(option.correct()),option.sortOrder()==null?index++:option.sortOrder());
            }
            String placeholders=marks(orders.size());List<Object> params=new ArrayList<>();params.add(parent.parentQuestionId());params.addAll(orders);
            archived+=jdbc.update("UPDATE question_resource SET status='archived',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE parent_question_id=? AND derivation_type='remedial_step' AND derivation_order NOT IN ("+placeholders+")",params.toArray());
        }
        String importId=UUID.randomUUID().toString();knowledge.audit(actorId,"REMEDIAL_QUESTIONS_IMPORTED","remedial_question_batch",importId,Map.of("parentCount",request.parents().size(),"created",created,"updated",updated,"archived",archived));
        return new ImportResult(request.schemaVersion(),request.parents().size(),created,updated,archived);
    }
    private void validateStep(StepInput step){List<QuestionContractValidator.Option> options=safe(step.options()).stream().map(o->new QuestionContractValidator.Option(o.key(),o.text(),Boolean.TRUE.equals(o.correct()))).toList();QuestionContractValidator.validateLegacy(step.questionType(),step.presentationType(),step.gradingMode(),step.standardAnswer(),step.analysisMarkdown(),options).ifPresent(m->{throw bad(m);});}
    private boolean formalParent(String id){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM question_resource WHERE id=? AND parent_question_id IS NULL AND status='published' AND question_type IN ('single_choice','multiple_choice','true_false','solution')",Integer.class,id);return n!=null&&n>0;}
    private JsonNode jsonNode(String value){try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static <T> List<T> safe(List<T> value){return value==null?List.of():value;}
    private static String value(String v){return v==null||v.isBlank()?null:v.trim();}
    private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
