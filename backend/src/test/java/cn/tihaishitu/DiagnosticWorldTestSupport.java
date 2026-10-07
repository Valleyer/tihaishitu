package cn.tihaishitu;

import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learning.DiagnosticLearningService;
import cn.tihaishitu.world.WorldActionContext;
import cn.tihaishitu.world.WorldRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class DiagnosticWorldTestSupport {
    /** 直接驱动保留诊断状态机时使用的执行上下文世界（与正式 Learner World 一致）。 */
    static final String DIAGNOSIS_WORLD = WorldRegistry.ANCIENT_OFFICIAL;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired QuestionAttemptStore attempts;
    @Autowired DiagnosticLearningService diagnostics;

    record Scenario(String book, String target, List<String> dependencies, List<String> fillers,
                    String rootQuestion) {}
    record ExamScenario(String book, List<String> points) {}

    Scenario scenario(int dependencyCount, boolean dependencyQuestions) {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'诊断文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','诊断章','',0,1)", chapter, book);
        String target = knowledge("TARGET");
        List<String> dependencies = new ArrayList<>(), fillers = new ArrayList<>();
        membership(book, chapter, target, 0);
        for (int index = 0; index < dependencyCount; index++) {
            String dependency = knowledge("DEP" + index);
            dependencies.add(dependency);
            membership(book, chapter, dependency, index + 1);
            if (dependencyQuestions) {
                question(2, List.of(relation(dependency, "core")));
                question(1, List.of(relation(dependency, "core")));
            }
        }
        for (int index = 0; index < 4; index++) {
            String filler = knowledge("FILL" + index);
            fillers.add(filler);
            membership(book, chapter, filler, dependencyCount + index + 1);
            question(2, List.of(relation(filler, "core")));
        }
        List<Relation> rootRelations = new ArrayList<>();
        rootRelations.add(relation(target, "core"));
        dependencies.forEach(dependency -> rootRelations.add(relation(dependency, "auxiliary")));
        String root = question(2, rootRelations);
        return new Scenario(book, target, List.copyOf(dependencies), List.copyOf(fillers), root);
    }

    ExamScenario examScenario() {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'考试文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'E','考试章','',0,1)", chapter, book);
        List<String> points = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String point = knowledge("EXAM" + index);
            points.add(point);
            membership(book, chapter, point, index);
            question(2, List.of(relation(point, "core")));
            question(1, List.of(relation(point, "core")));
        }
        return new ExamScenario(book, List.copyOf(points));
    }

    Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"诊断\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    String configureLearner(String username, Scenario scenario) {
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class, username);
        jdbc.update("UPDATE learner_study_profile SET focus_mode='manual' WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,0)", learner, scenario.target());
        for (String dependency : scenario.dependencies()) ready(learner, dependency);
        return learner;
    }

    /**
     * 诊断根题关联的知识点顺序：target 在前，依赖在后，与 root 题目的
     * question_resource_knowledge.sort_order 一致；诊断会话据此展开依赖。
     */
    List<String> rootKnowledgePointIds(Scenario scenario) {
        List<String> ids = new ArrayList<>();
        ids.add(scenario.target());
        ids.addAll(scenario.dependencies());
        return List.copyOf(ids);
    }

    /**
     * 直接驱动保留诊断状态机：在 Learner World 执行上下文里创建一道正式 attempt。
     * 根题必须带上真实 knowledgePointIds，probe / remediation / recheck 只需沿用正式题。
     */
    String createDiagnosisAttempt(String learner, String questionId, List<String> knowledgePointIds,
                                  String targetKnowledgePointId, String evidenceMode,
                                  String diagnosisSessionId, String diagnosisRole) {
        String attemptId = UUID.randomUUID().toString();
        WorldActionContext.run(learner, DIAGNOSIS_WORLD, () -> {
            attempts.create(attemptId, DIAGNOSIS_WORLD, questionId, diagnosisQuestion(questionId, knowledgePointIds),
                    mapper.getNodeFactory().booleanNode(true), "auto", targetKnowledgePointId, evidenceMode, 2,
                    diagnosisSessionId, diagnosisRole);
            return null;
        });
        return attemptId;
    }

    /**
     * 判分并推进诊断。allowed 对应正式出题冻结的可练知识点集合：
     * 在集合内的依赖为 pending，否则为 unavailable。
     */
    DiagnosticLearningService.GradingResult gradeDiagnosisAttempt(String learner, String attemptId, boolean correct,
                                                                  Set<String> allowed, Instant at) {
        return WorldActionContext.run(learner, DIAGNOSIS_WORLD, () -> {
            QuestionAttemptStore.Snapshot snapshot = attempts.find(attemptId, DIAGNOSIS_WORLD);
            attempts.recordAnswer(snapshot, mapper.getNodeFactory().booleanNode(correct), correct, at);
            return diagnostics.handleGradedAttempt(snapshot, correct ? "correct" : "wrong", "automatic", at, allowed);
        });
    }

    DiagnosticLearningService.Directive nextDiagnosisDirective(String learner, String diagnosisId) {
        return WorldActionContext.run(learner, DIAGNOSIS_WORLD, () -> diagnostics.nextDirective(diagnosisId));
    }

    /** 依赖没有可发的正式题时，由调用方标记探针不可用。 */
    void markProbeUnavailable(String learner, String diagnosisId, String pointId) {
        WorldActionContext.run(learner, DIAGNOSIS_WORLD, () -> {
            diagnostics.markProbeUnavailable(diagnosisId, pointId);
            return null;
        });
    }

    void abandonDiagnosis(String learner, String diagnosisId) {
        WorldActionContext.run(learner, DIAGNOSIS_WORLD, () -> {
            diagnostics.abandon(diagnosisId);
            return null;
        });
    }

    /** 知识点下的一道正式题；没有就补一道，与 ready(...) 的兜底口径一致。 */
    String formalQuestion(String point) {
        List<String> questions = jdbc.queryForList("""
                SELECT q.id FROM question_resource q
                JOIN question_resource_knowledge qk ON qk.question_id=q.id
                WHERE qk.knowledge_point_id=? AND qk.relation_role='core' AND q.status='published'
                  AND q.parent_question_id IS NULL
                ORDER BY q.id
                """, String.class, point);
        return questions.isEmpty() ? question(2, List.of(relation(point, "core"))) : questions.get(0);
    }

    /** attempt 冻结的题目快照：诊断只读取 knowledgePointIds，其余字段不影响推进。 */
    private ObjectNode diagnosisQuestion(String questionId, List<String> knowledgePointIds) {
        ObjectNode question = mapper.createObjectNode();
        question.put("id", questionId);
        question.put("gradingMode", "auto");
        question.set("knowledgePointIds", mapper.valueToTree(knowledgePointIds));
        return question;
    }

    void initialize(Cookie cookie) throws Exception {
        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
    }

    JsonNode begin(Cookie cookie, String activity) throws Exception {
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"%s\"}".formatted(activity)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /**
     * 按「答对 / 答错」意图作答。判断题选项会在 attempt 级重排并同步 remap boolean standard，
     * 因此不能固定提交 true / false，必须对照本次 attempt 冻结的 standard。
     */
    JsonNode answer(Cookie cookie, JsonNode game, boolean correct) throws Exception {
        String attempt = game.path("attempt").path("id").asText();
        boolean standard = Boolean.parseBoolean(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attempt));
        return submitAnswer(cookie, game, correct == standard);
    }

    private JsonNode submitAnswer(Cookie cookie, JsonNode game, boolean value) throws Exception {
        String attempt = game.path("attempt").path("id").asText();
        String question = game.path("attempt").path("question").path("id").asText();
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/answers").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attempt, question, value)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode next(Cookie cookie, JsonNode game) throws Exception {
        String attempt = game.path("attempt").path("id").asText();
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/next").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\"}".formatted(attempt)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode abandon(Cookie cookie, JsonNode game) throws Exception {
        String run = game.path("adventure").path("run").path("id").asText();
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/activities/abandon").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"runId\":\"%s\"}".formatted(run)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode finish(Cookie cookie, JsonNode game) throws Exception {
        String run = game.path("adventure").path("run").path("id").asText();
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/activities/finish").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"runId\":\"%s\"}".formatted(run)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    String currentAttempt(JsonNode game) { return game.path("attempt").path("id").asText(); }
    String currentQuestion(JsonNode game) { return game.path("attempt").path("question").path("id").asText(); }
    JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    String knowledge(String label) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'诊断测试','节','章','core','active','','',0,1)
                """, id, label + "-" + id, label);
        return id;
    }

    String question(int difficulty, List<Relation> relations) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'诊断测试','custom','true_false','true_false','auto',?,'true','解析',?,'published',1)
                """, id, "题目-" + id, difficulty);
        trueFalseOptions(id);
        for (int index = 0; index < relations.size(); index++) {
            Relation relation = relations.get(index);
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,?,?)",
                    id, relation.point(), relation.role(), index);
        }
        return id;
    }

    private void trueFalseOptions(String questionId) { QuestionFixtures.trueFalseOptions(jdbc, questionId); }

    void membership(String book, String chapter, String point, int order) {
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                book, point, chapter, order);
    }

    void ready(String learner, String point) {
        List<String> formalQuestions = jdbc.queryForList("""
                SELECT q.id FROM question_resource q
                JOIN question_resource_knowledge qk ON qk.question_id=q.id
                WHERE qk.knowledge_point_id=? AND qk.relation_role='core' AND q.status='published'
                  AND q.parent_question_id IS NULL
                """, String.class, point);
        if (formalQuestions.isEmpty()) {
            String unavailableDependency = knowledge("BLOCKER");
            formalQuestions = List.of(question(2, List.of(
                    relation(point, "core"), relation(unavailableDependency, "auxiliary"))));
        }
        jdbc.update("""
                INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,
                    target_difficulty,evidence_count,correct_streak,wrong_streak,last_outcome,last_evidence_at,
                    last_correct_at,model_version,revision)
                VALUES (?,?,100,365,2,1,1,0,'correct',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v3-question-reinforcement',1)
                """, learner, point);
        formalQuestions.forEach(question -> jdbc.update("""
                INSERT INTO learner_question_mastery(learner_id,knowledge_point_id,question_id,score,
                    first_correct_at,last_correct_at,last_reward_date,last_decay_date,last_assessment,last_attempt_at,
                    decay_frozen,revision)
                VALUES (?,?,?,100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_DATE,CURRENT_DATE,NULL,CURRENT_TIMESTAMP,TRUE,1)
                """, learner, point, question));
    }

    static Relation relation(String point, String role) { return new Relation(point, role); }
    record Relation(String point, String role) {}
}
