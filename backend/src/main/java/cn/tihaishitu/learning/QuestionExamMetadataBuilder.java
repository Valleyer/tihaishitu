package cn.tihaishitu.learning;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 正式题面 metadata 的唯一构造器。
 *
 * <p>Learning Hub Practice（{@code LearnerPracticeService}）与 World / 副本
 * （{@code GameActionService}）都调用本类，避免出现“Hub 显示一套、World 显示另一套”。</p>
 *
 * <p>产出结构会冻结进 {@code study_attempt.question_snapshot_json.examMetadata}，
 * 因此同一个 attempt 刷新后 metadata 不变，前端不需要在刷新时重新查数据库拼接。</p>
 *
 * <pre>
 * {
 *   "subjectName": "数学一",
 *   "sourceName": "2022年全国硕士研究生招生考试数学一",
 *   "examYear": 2022,
 *   "questionNumber": "2022-3",          // 数据库原始值
 *   "displayQuestionNumber": "3",         // UI 只使用这个
 *   "examLabel": "2022年考研数学一真题",
 *   "knowledgePoints": [ {id,name,role} ] // core + auxiliary 都返回
 * }
 * </pre>
 */
@Service
public class QuestionExamMetadataBuilder {
    private final KnowledgeQuestionPoolService pool;
    private final ObjectMapper mapper;

    public QuestionExamMetadataBuilder(KnowledgeQuestionPoolService pool, ObjectMapper mapper) {
        this.pool = pool;
        this.mapper = mapper;
    }

    /** 构造可直接写入 attempt snapshot 的 metadata 节点。 */
    public ObjectNode build(String questionId, String fallbackSourceName) {
        ObjectNode metadata = mapper.createObjectNode();
        var source = questionId == null ? null : pool.questionSource(questionId).orElse(null);
        String subjectName = source == null ? null : trimToNull(source.subjectName());
        if (subjectName != null) metadata.put("subjectName", subjectName);
        String sourceName = source == null ? null : trimToNull(source.sourceName());
        metadata.put("sourceName", sourceName == null
                ? (trimToNull(fallbackSourceName) == null ? "全服题库" : fallbackSourceName.trim())
                : sourceName);
        Integer examYear = source == null ? null : source.examYear();
        if (examYear != null) metadata.put("examYear", examYear);
        String questionNumber = source == null ? null : trimToNull(source.questionNumber());
        if (questionNumber != null) metadata.put("questionNumber", questionNumber);
        String displayNumber = QuestionNumberFormatter.display(questionNumber, examYear);
        if (displayNumber != null) metadata.put("displayQuestionNumber", displayNumber);
        String examLabel = KnowledgeQuestionExamLabel.generate(subjectName, examYear);
        if (examLabel != null) metadata.put("examLabel", examLabel);
        metadata.set("knowledgePoints", knowledgePointTags(questionId));
        return metadata;
    }

    /** 题目全部知识点标签：core 与 auxiliary 都返回，role 只表达主次。 */
    private ArrayNode knowledgePointTags(String questionId) {
        ArrayNode tags = mapper.createArrayNode();
        if (questionId == null) return tags;
        List<KnowledgePointTag> values = pool.questionKnowledgeTags(questionId);
        values.forEach(tag -> {
            ObjectNode node = tags.addObject();
            node.put("id", tag.id());
            node.put("name", tag.name());
            node.put("role", tag.role());
        });
        return tags;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
