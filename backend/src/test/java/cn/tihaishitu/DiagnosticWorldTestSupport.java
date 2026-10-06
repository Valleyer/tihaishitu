package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learning.AdaptiveStudyPlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class DiagnosticWorldTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean AdaptiveStudyPlanner planner;

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
        // 目标知识点只保留 root 一道正式题：
        // 正式题抽取现在在候选池内随机，多留同知识点的干扰题会让“第一题必须是 root”的断言不确定；
        // 同时也能覆盖“知识点只有一道题时核验 / 补救复用该题”的真实场景。
        deleteExtraTargetQuestions(target, root);
        return new Scenario(book, target, List.copyOf(dependencies), List.copyOf(fillers), root);
    }

    /** 删除某个知识点除 keep 之外的正式父题及其关系，保证该知识点的候选题唯一。 */
    private void deleteExtraTargetQuestions(String point, String keep) {
        List<String> others = jdbc.queryForList("""
                SELECT DISTINCT q.id FROM question_resource q
                JOIN question_resource_knowledge qk ON qk.question_id = q.id
                WHERE qk.knowledge_point_id = ? AND q.id <> ?
                """, String.class, point, keep);
        for (String id : others) {
            jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id=?", id);
            jdbc.update("DELETE FROM question_resource_option WHERE question_id=?", id);
            jdbc.update("DELETE FROM question_resource WHERE id=?", id);
        }
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

    void forceScenarioPlan(Scenario scenario) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        allowed.add(scenario.target());
        allowed.addAll(scenario.dependencies());
        allowed.addAll(scenario.fillers());
        doAnswer(invocation -> {
            int count = invocation.getArgument(2);
            List<String> targets = new ArrayList<>();
            targets.add(scenario.target());
            targets.addAll(scenario.fillers());
            return new AdaptiveStudyPlanner.AdaptiveStudyPlan(allowed, List.copyOf(targets.subList(0, count)));
        }).when(planner).randomPlan(anyString(), anySet(), anyInt());
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

    JsonNode answer(Cookie cookie, JsonNode game, boolean value) throws Exception {
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
        for (int index = 0; index < relations.size(); index++) {
            Relation relation = relations.get(index);
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,?,?)",
                    id, relation.point(), relation.role(), index);
        }
        return id;
    }

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
