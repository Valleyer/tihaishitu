package cn.tihaishitu.catalog;

import cn.tihaishitu.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class CatalogValidator {
    private static final Set<String> TYPES = Set.of("single_choice", "multiple_choice", "true_false");

    public void validate(QuestionBankDto bank) {
        if (bank == null) throw bad("题库文件不能为空。");
        validateId(bank.id(), "文集");
        if (blank(bank.name())) throw bad("文集名称不能为空。");
        if (bank.weight() < 0 || bank.weight() > 100) throw bad("文集抽取权重必须在 0–100 之间。");
        if (bank.knowledgePoints().isEmpty()) throw bad("文集至少需要一个细分知识点。");
        if (bank.questions().isEmpty()) throw bad("文集至少需要一道题。");

        Set<String> pointIds = new HashSet<>();
        for (KnowledgePointDto point : bank.knowledgePoints()) {
            validateId(point.id(), "知识点");
            if (!pointIds.add(point.id())) throw bad("知识点 UUID 重复：" + point.id());
            if (blank(point.name()) || blank(point.subject()) || blank(point.category()))
                throw bad("知识点名称、科目和分类不能为空：" + point.id());
        }
        for (KnowledgePointDto point : bank.knowledgePoints()) {
            if (point.parentId() != null && !point.parentId().isBlank() && !pointIds.contains(point.parentId()))
                throw bad("知识点 parentId 不在当前文集中：" + point.id());
            for (String id : point.prerequisites())
                if (!pointIds.contains(id)) throw bad("知识点前置引用不存在：" + point.id() + " -> " + id);
        }

        Set<String> questionIds = new HashSet<>();
        for (QuestionDto question : bank.questions()) {
            validateId(question.id(), "题目");
            if (!questionIds.add(question.id())) throw bad("题目 UUID 重复：" + question.id());
            if (!TYPES.contains(question.type())) throw bad("题型不受支持：" + question.id());
            if (blank(question.question()) || blank(question.explanation()))
                throw bad("题干和解析不能为空：" + question.id());
            if (question.difficulty() < 1 || question.difficulty() > 5)
                throw bad("题目 difficulty 必须在 1–5 之间：" + question.id());
            if (question.knowledgePointIds().size() < 1 || question.knowledgePointIds().size() > 3)
                throw bad("每道题必须关联 1–3 个知识点：" + question.id());
            for (String id : question.knowledgePointIds())
                if (!pointIds.contains(id)) throw bad("题目引用了不存在的知识点：" + question.id() + " -> " + id);
            validateAnswer(question);
        }
    }

    public void validateId(String id, String label) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id.toLowerCase()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw bad(label + " id 必须是标准 UUID：" + id);
        }
    }

    private void validateAnswer(QuestionDto question) {
        JsonNode answer = question.answer();
        if ("true_false".equals(question.type())) {
            if (answer == null || !answer.isBoolean()) throw bad("判断题答案必须是 true 或 false：" + question.id());
            if (!question.options().keySet().equals(Set.of("true", "false")))
                throw bad("判断题选项必须固定为 true/false：" + question.id());
            return;
        }
        if (question.options().size() < 2) throw bad("选择题至少需要两个选项：" + question.id());
        if ("single_choice".equals(question.type())) {
            if (answer == null || !answer.isTextual() || !question.options().containsKey(answer.asText()))
                throw bad("单选题答案必须引用有效选项键：" + question.id());
            return;
        }
        if (answer == null || !answer.isArray() || answer.size() < 2)
            throw bad("多选题答案至少包含两个选项键：" + question.id());
        Set<String> selected = new HashSet<>();
        for (JsonNode value : answer) {
            if (!value.isTextual() || !question.options().containsKey(value.asText()) || !selected.add(value.asText()))
                throw bad("多选题答案包含无效或重复选项键：" + question.id());
        }
    }

    ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
