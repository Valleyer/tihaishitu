package cn.tihaishitu.catalog;

import cn.tihaishitu.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CatalogNormalizer {
    private static final Set<String> TYPES = Set.of(
            "single_choice", "multiple_choice", "true_false", "solution");

    public QuestionBankDto normalize(QuestionBankDto source) {
        require(source != null, "文集不能为空。");
        require(!text(source.name()).isBlank(), "文集名称不能为空。");
        require(source.questions().size() <= 10_000, "单个文集最多包含 10000 道题。");

        String sourceBankId = defaultText(source.id(), source.name());
        String bankId = uuid(source.id(), "bank|" + sourceBankId);
        Map<String, String> pointIds = new LinkedHashMap<>();
        for (KnowledgePointDto point : source.knowledgePoints()) {
            require(point != null, "知识点不能为空。");
            String oldId = defaultText(point.id(), point.name());
            require(pointIds.putIfAbsent(oldId, uuid(point.id(), "point|" + bankId + "|" + oldId)) == null,
                    "知识点 id 重复：" + oldId);
        }

        Map<String, KnowledgePointDto> points = new LinkedHashMap<>();
        for (KnowledgePointDto point : source.knowledgePoints()) {
            String oldId = defaultText(point.id(), point.name());
            KnowledgePointDto normalized = normalizePoint(point, pointIds.get(oldId), pointIds);
            points.put(normalized.id(), normalized);
        }

        List<QuestionDto> questions = new ArrayList<>();
        Set<String> questionIds = new LinkedHashSet<>();
        for (QuestionDto question : source.questions()) {
            require(question != null, "题目不能为空。");
            List<String> oldPointIds = new ArrayList<>(question.knowledgePointIds());
            if (oldPointIds.isEmpty()) {
                String seed = text(question.subject()) + "|" + text(question.category()) + "|" + text(question.chapter());
                String oldFallbackId = "legacy-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
                String fallbackId = pointIds.computeIfAbsent(oldFallbackId,
                        ignored -> uuid(null, "point|" + bankId + "|" + oldFallbackId));
                oldPointIds.add(oldFallbackId);
                points.putIfAbsent(fallbackId, new KnowledgePointDto(
                        fallbackId,
                        text(question.chapter()).isBlank() ? "未分章" : text(question.chapter()),
                        defaultText(question.subject(), "自修"),
                        defaultText(question.category(), "通识"),
                        "旧题库自动归入此兼容知识点，可在管理端继续细分。",
                        "先辨认本类题使用的定义、条件与常见变形，再结合例题复核。",
                        null, List.of(), List.of("兼容知识点")
                ));
            }
            List<String> resolvedPoints = oldPointIds.stream().map(pointIds::get).toList();
            require(resolvedPoints.stream().noneMatch(java.util.Objects::isNull),
                    "题目“" + question.id() + "”引用了不存在的知识点。");
            String oldQuestionId = defaultText(question.id(), question.question());
            String questionId = uuid(question.id(), "question|" + bankId + "|" + oldQuestionId);
            QuestionDto normalized = normalizeQuestion(question, questionId, resolvedPoints);
            require(questionIds.add(normalized.id()), "题目 id 重复：" + oldQuestionId);
            questions.add(normalized);
        }

        for (KnowledgePointDto point : points.values()) {
            require(point.parentId() == null || points.containsKey(point.parentId()),
                    "知识点“" + point.name() + "”引用了不存在的上位知识点。");
            require(!point.prerequisites().contains(point.id()),
                    "知识点“" + point.name() + "”不能把自身设为前置知识点。");
            require(point.prerequisites().stream().allMatch(points::containsKey),
                    "知识点“" + point.name() + "”引用了不存在的前置知识点。");
        }
        return new QuestionBankDto(bankId, source.name().trim(),
                defaultText(source.description(), "服务器统一题库"), List.copyOf(points.values()),
                questions, source.enabled(), source.weight() < 0 ? 1 : source.weight());
    }

    private KnowledgePointDto normalizePoint(KnowledgePointDto point, String id, Map<String, String> pointIds) {
        require(!text(point.name()).isBlank(), "知识点名称不能为空。");
        String parentId = point.parentId() == null ? null : pointIds.get(point.parentId());
        List<String> prerequisites = point.prerequisites().stream().map(pointIds::get).toList();
        return new KnowledgePointDto(id, point.name().trim(), defaultText(point.subject(), "自修"),
                defaultText(point.category(), "通识"),
                defaultText(point.description(), "用于定位这一类题的核心能力。"),
                defaultText(point.explanation(), point.description()), parentId, prerequisites, point.tags());
    }

    private QuestionDto normalizeQuestion(QuestionDto question, String id, List<String> pointIds) {
        require(TYPES.contains(question.type()), "题目“" + question.id() + "”题型不受支持。");
        require(!text(question.question()).isBlank(), "题目“" + question.id() + "”题干不能为空。");
        require(question.answer() != null && !question.answer().isNull(), "题目“" + question.id() + "”缺少答案。");
        require(pointIds.size() >= 1 && pointIds.size() <= 3, "题目“" + question.id() + "”须关联 1–3 个知识点。");
        require(question.difficulty() >= 1 && question.difficulty() <= 5, "题目“" + question.id() + "”难度必须为 1–5。");
        require(question.frequency() >= 1 && question.frequency() <= 5, "题目“" + question.id() + "”频率必须为 1–5。");
        if ("solution".equals(question.type())) {
            require("self_assessment".equals(question.presentationType())
                            && "self_assessment".equals(question.gradingMode()),
                    "题目“" + question.id() + "”综合题必须使用自评展示与自评判题。");
            require(question.options().isEmpty(), "题目“" + question.id() + "”综合题不能提供客观题选项。");
        } else {
            require(question.type().equals(question.presentationType()) && "auto".equals(question.gradingMode()),
                    "题目“" + question.id() + "”客观题的展示与判题模式不匹配。");
            require(question.options().size() >= 2 && question.options().size() <= 6,
                    "题目“" + question.id() + "”需要 2–6 个选项。");
        }
        return new QuestionDto(id, defaultText(question.subject(), "自修"),
                defaultText(question.category(), "通识"), defaultText(question.chapter(), "未分章"),
                question.type(), question.originalType(), question.presentationType(), question.gradingMode(),
                question.question().trim(), question.options(), question.answer(),
                defaultText(question.explanation(), "请对照标准答案复习。"), question.aliases(), question.keywords(),
                question.difficulty(), question.frequency(), question.tags(),
                List.copyOf(new LinkedHashSet<>(pointIds)), question.enabled());
    }

    private static String uuid(String candidate, String seed) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate).toString();
            } catch (IllegalArgumentException ignored) {
                // 旧题库语义 id 入库时转换成稳定 UUID。
            }
        }
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String defaultText(String value, String fallback) {
        return text(value).isBlank() ? defaultTextValue(fallback) : value.trim();
    }

    private static String defaultTextValue(String value) {
        return value == null || value.isBlank() ? "请结合例题复习。" : value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
