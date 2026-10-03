package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.world.WorldRegistry;
import cn.tihaishitu.world.WorldStateStore;

@Service
public class HistoryQuestionService {
    private final GameStore games;
    private final QuestionAttemptStore attempts;
    private final ObjectMapper mapper;
    private final WorldStateStore worldStates;

    public HistoryQuestionService(GameStore games, QuestionAttemptStore attempts, ObjectMapper mapper,
                                  WorldStateStore worldStates) {
        this.games = games;
        this.attempts = attempts;
        this.mapper = mapper;
        this.worldStates = worldStates;
    }

    @Transactional(readOnly = true)
    public ObjectNode recoverForLearner(HistoryQuestionsRequest request) {
        String learnerId = LearnerContext.learnerId();
        ObjectNode world = worldStates.find(learnerId, WorldRegistry.ANCIENT_OFFICIAL);
        Map<String, String> recordedQuestions = recordedQuestions(world);
        var requested = new LinkedHashSet<String>();
        request.attemptIds().forEach(attemptId -> {
            if (recordedQuestions.containsKey(attemptId)) requested.add(attemptId);
        });
        Map<String, QuestionAttemptStore.HistorySnapshot> snapshots =
                attempts.findForLearnerHistory(learnerId, requested);
        ArrayNode recovered = mapper.createArrayNode();
        for (String attemptId : requested) {
            String questionId = recordedQuestions.get(attemptId);
            QuestionAttemptStore.HistorySnapshot snapshot = snapshots.get(attemptId);
            if (snapshot == null || !"graded".equals(snapshot.status())
                    || !questionId.equals(snapshot.questionId()) || snapshot.question() == null) continue;
            ObjectNode item = recovered.addObject();
            item.put("attemptId", attemptId);
            item.put("questionId", questionId);
            item.set("question", snapshot.question());
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("questions", recovered);
        return response;
    }

    @Transactional(readOnly = true)
    public ObjectNode recover(String gameId, HistoryQuestionsRequest request) {
        ObjectNode game = games.findObject(gameId);
        Map<String, String> recordedQuestions = recordedQuestions(game);
        var requested = new LinkedHashSet<String>();
        request.attemptIds().forEach(attemptId -> {
            if (recordedQuestions.containsKey(attemptId)) requested.add(attemptId);
        });

        Map<String, QuestionAttemptStore.HistorySnapshot> snapshots =
                attempts.findForHistory(gameId, requested);

        ArrayNode recovered = mapper.createArrayNode();
        for (String attemptId : requested) {
            String questionId = recordedQuestions.get(attemptId);
            QuestionAttemptStore.HistorySnapshot snapshot = snapshots.get(attemptId);
            JsonNode question = null;
            if (snapshot != null && "graded".equals(snapshot.status())
                    && questionId.equals(snapshot.questionId()) && snapshot.question() != null) {
                question = snapshot.question();
            }
            if (question == null) continue;
            ObjectNode item = recovered.addObject();
            item.put("attemptId", attemptId);
            item.put("questionId", questionId);
            item.set("question", question);
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("questions", recovered);
        return response;
    }

    private static Map<String, String> recordedQuestions(ObjectNode game) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode record : game.withArray("records")) {
            String attemptId = record.path("attemptId").asText();
            String questionId = record.path("questionId").asText();
            if (!attemptId.isBlank() && !questionId.isBlank()) result.put(attemptId, questionId);
        }
        return result;
    }
}
