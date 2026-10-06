package cn.tihaishitu.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class RemedialQuestionStore {
    public record ParentInfo(String parentQuestionId,int order){}
    public record Step(String id, int order, String trainingGoal, ObjectNode question,
                       JsonNode standard, String gradingMode, int difficulty) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public RemedialQuestionStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public List<Step> steps(String parentQuestionId) {
        return jdbc.query("""
                SELECT id,derivation_order,training_goal,question_type,presentation_type,grading_mode,
                       content_markdown,standard_answer_json,analysis_markdown,difficulty
                  FROM question_resource
                 WHERE parent_question_id=? AND derivation_type='remedial_step' AND status='published'
                 ORDER BY derivation_order,id
                """, (rs,row) -> {
            ObjectNode question=mapper.createObjectNode();
            question.put("id",rs.getString("id")); question.put("questionType",rs.getString("question_type"));
            question.put("presentationType",rs.getString("presentation_type"));
            question.put("gradingMode",rs.getString("grading_mode"));
            question.put("content",rs.getString("content_markdown"));
            question.put("contentMarkdown",rs.getString("content_markdown"));
            question.put("explanation",rs.getString("analysis_markdown"));
            Map<String,String> options=new LinkedHashMap<>();
            jdbc.query("SELECT option_key,option_text FROM question_resource_option WHERE question_id=? ORDER BY sort_order,option_key",
                    (RowCallbackHandler) option -> options.put(option.getString(1),option.getString(2)),rs.getString("id"));
            question.set("options",mapper.valueToTree(options));
            return new Step(rs.getString("id"),rs.getInt("derivation_order"),rs.getString("training_goal"),question,
                    json(rs.getString("standard_answer_json")),rs.getString("grading_mode"),rs.getInt("difficulty"));
        },parentQuestionId);
    }
    public ParentInfo parentInfo(String questionId){
        return jdbc.query("SELECT parent_question_id,derivation_order FROM question_resource WHERE id=? AND derivation_type='remedial_step'",
                (rs,row)->new ParentInfo(rs.getString(1),rs.getInt(2)),questionId).stream().findFirst().orElse(null);
    }

    public Step parent(String questionId){
        return jdbc.query("""
                SELECT id,0 derivation_order,'' training_goal,question_type,presentation_type,grading_mode,
                       content_markdown,standard_answer_json,analysis_markdown,difficulty
                  FROM question_resource WHERE id=? AND parent_question_id IS NULL AND status='published'
                """,(rs,row)->{
            ObjectNode question=mapper.createObjectNode();question.put("id",rs.getString("id"));
            question.put("questionType",rs.getString("question_type"));question.put("presentationType",rs.getString("presentation_type"));question.put("gradingMode",rs.getString("grading_mode"));question.put("content",rs.getString("content_markdown"));question.put("contentMarkdown",rs.getString("content_markdown"));question.put("explanation",rs.getString("analysis_markdown"));
            Map<String,String> options=new LinkedHashMap<>();jdbc.query("SELECT option_key,option_text FROM question_resource_option WHERE question_id=? ORDER BY sort_order,option_key",(RowCallbackHandler) o->options.put(o.getString(1),o.getString(2)),questionId);question.set("options",mapper.valueToTree(options));
            return new Step(rs.getString("id"),0,"",question,json(rs.getString("standard_answer_json")),rs.getString("grading_mode"),rs.getInt("difficulty"));
        },questionId).stream().findFirst().orElseThrow();
    }
    private JsonNode json(String value){try{return mapper.readTree(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
}
