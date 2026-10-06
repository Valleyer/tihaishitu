package cn.tihaishitu.manage;

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
public class KnowledgeGuideManagementService {
    public record ExportRequest(List<String> knowledgePointIds,String bookId,String chapterId){}
    public record GuideInput(String knowledgePointId,String knowledgePointCode,String contentMarkdown){}
    public record ImportRequest(String schemaVersion,List<GuideInput> guides){}
    public record ImportResult(String schemaVersion,int created,int updated){}
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final KnowledgeManagementStore knowledge;
    public KnowledgeGuideManagementService(JdbcTemplate jdbc,ObjectMapper mapper,KnowledgeManagementStore knowledge){this.jdbc=jdbc;this.mapper=mapper;this.knowledge=knowledge;}

    public Map<String,Object> export(ExportRequest request){
        List<String> clauses=new ArrayList<>(List.of("k.status='active'"));List<Object> args=new ArrayList<>();
        if(request!=null&&request.knowledgePointIds()!=null&&!request.knowledgePointIds().isEmpty()){clauses.add("k.id IN ("+marks(request.knowledgePointIds().size())+")");args.addAll(request.knowledgePointIds());}
        if(request!=null&&value(request.bookId())!=null){clauses.add("bk.bank_id=?");args.add(request.bookId());}
        if(request!=null&&value(request.chapterId())!=null){clauses.add("bk.chapter_id=?");args.add(request.chapterId());}
        List<Map<String,Object>> points=jdbc.query("""
            SELECT DISTINCT k.id,k.code,k.name,k.description FROM global_knowledge_point k
            LEFT JOIN question_bank_knowledge bk ON bk.knowledge_point_id=k.id WHERE %s ORDER BY k.sort_order,k.code
            """.formatted(String.join(" AND ",clauses)),(rs,row)->{
            String id=rs.getString("id");Map<String,Object> item=new LinkedHashMap<>();item.put("knowledgePointId",id);item.put("code",rs.getString("code"));item.put("name",rs.getString("name"));item.put("description",rs.getString("description"));
            item.put("aliases",jdbc.query("SELECT alias FROM knowledge_alias WHERE knowledge_point_id=? ORDER BY alias",(a,i)->a.getString(1),id));
            item.put("memberships",jdbc.query("SELECT b.id bookId,b.name bookName,c.id chapterId,c.name chapterName FROM question_bank_knowledge bk JOIN question_bank b ON b.id=bk.bank_id JOIN question_bank_chapter c ON c.id=bk.chapter_id WHERE bk.knowledge_point_id=? ORDER BY b.name,c.sort_order",(m,i)->Map.of("bookId",m.getString(1),"bookName",m.getString(2),"chapterId",m.getString(3),"chapterName",m.getString(4)),id));
            item.put("formalQuestions",jdbc.query("SELECT q.content_markdown,q.standard_answer_json,q.analysis_markdown FROM question_resource_knowledge r JOIN question_resource q ON q.id=r.question_id WHERE r.knowledge_point_id=? AND r.relation_role='core' AND q.status='published' AND q.parent_question_id IS NULL ORDER BY q.id",(q,i)->Map.of("contentMarkdown",q.getString(1),"standardAnswer",jsonNode(q.getString(2)),"analysisMarkdown",q.getString(3)),id));return item;
        },args.toArray());
        return Map.of("schemaVersion","knowledge-guide-generation/v1","generationInstructions",Map.of("format","Markdown","latex",true,"html",false,"focus","怎么识别、为什么这样做；包含定义、公式、适用条件、方法、易错点和必要小例子"),"knowledgePoints",points);
    }

    @Transactional public ImportResult importBatch(JsonNode document,String actorId){
        ImportRequest request;try{request=mapper.treeToValue(document,ImportRequest.class);}catch(JsonProcessingException e){throw bad("知识讲解批次 JSON 字段类型不正确。");}
        if(request==null||!"knowledge-guide-batch/v1".equals(request.schemaVersion()))throw bad("schemaVersion 必须为 knowledge-guide-batch/v1。");
        if(request.guides()==null||request.guides().isEmpty())throw bad("至少需要一条知识讲解。");int created=0,updated=0;
        for(GuideInput guide:request.guides()){
            if(guide==null||value(guide.contentMarkdown())==null||value(guide.knowledgePointId())==null&&value(guide.knowledgePointCode())==null)throw bad("知识讲解必须提供 ID/code 和非空 Markdown。");
            List<Map<String,Object>> rows=jdbc.query("SELECT id,code FROM global_knowledge_point WHERE status='active' AND (id=? OR code=?)",(rs,row)->Map.of("id",rs.getString(1),"code",rs.getString(2)),value(guide.knowledgePointId()),value(guide.knowledgePointCode()));
            Set<String> ids=new HashSet<>();rows.forEach(r->ids.add((String)r.get("id")));if(ids.size()!=1)throw bad("知识点 ID/code 不存在或未指向同一知识点。");String id=ids.iterator().next();
            Map<String,Object> resolved=rows.stream().filter(row->id.equals(row.get("id"))).findFirst().orElseThrow();
            if(value(guide.knowledgePointId())!=null&&!id.equals(value(guide.knowledgePointId()))
                    ||value(guide.knowledgePointCode())!=null&&!resolved.get("code").equals(value(guide.knowledgePointCode())))
                throw bad("知识点 ID/code 不存在或未指向同一知识点。");
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point_guide WHERE knowledge_point_id=?",Integer.class,id);
            if(count!=null&&count>0){jdbc.update("UPDATE knowledge_point_guide SET content_markdown=?,updated_by=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE knowledge_point_id=?",guide.contentMarkdown().trim(),actorId,id);updated++;}
            else{jdbc.update("INSERT INTO knowledge_point_guide(knowledge_point_id,content_markdown,created_by,updated_by,revision) VALUES (?,?,?,?,1)",id,guide.contentMarkdown().trim(),actorId,actorId);created++;}
        }
        String importId=UUID.randomUUID().toString();knowledge.audit(actorId,"KNOWLEDGE_GUIDES_IMPORTED","knowledge_guide_batch",importId,Map.of("created",created,"updated",updated));return new ImportResult(request.schemaVersion(),created,updated);
    }
    private JsonNode jsonNode(String v){try{return mapper.readTree(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String value(String v){return v==null||v.isBlank()?null:v.trim();}private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
