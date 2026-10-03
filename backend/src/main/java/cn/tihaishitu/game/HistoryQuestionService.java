package cn.tihaishitu.game;

import cn.tihaishitu.catalog.QuestionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class HistoryQuestionService {
    private final GameStore games;
    private final QuestionAttemptStore attempts;
    private final KnowledgeQuestionPoolStore questions;
    private final ObjectMapper mapper;

    public HistoryQuestionService(GameStore games, QuestionAttemptStore attempts,
                                  KnowledgeQuestionPoolStore questions, ObjectMapper mapper) {
        this.games = games;
        this.attempts = attempts;
        this.questions = questions;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ObjectNode recover(String gameId, HistoryQuestionsRequest request) {
        ObjectNode game = games.findObject(gameId);
        Map<String, String> recordedQuestions = recordedQuestions(game);
        Set<String> requested = new LinkedHashSet<>();
        request.attemptIds().forEach(attemptId -> {
            if (recordedQuestions.containsKey(attemptId)) requested.add(attemptId);
        });

        Map<String, QuestionAttemptStore.HistorySnapshot> snapshots =
                attempts.findForHistory(gameId, requested);
        Set<String> fallbackQuestionIds = new LinkedHashSet<>();
        for (String attemptId : requested) {
            QuestionAttemptStore.HistorySnapshot snapshot = snapshots.get(attemptId);
            if (snapshot == null || ("graded".equals(snapshot.status()) && snapshot.question() == null)) {
                fallbackQuestionIds.add(recordedQuestions.get(attemptId));
            }
        }
        Map<String, QuestionDto> fallbackQuestions = questions.questionsByIds(fallbackQuestionIds);

        ArrayNode recovered = mapper.createArrayNode();
        for (String attemptId : requested) {
            String questionId = recordedQuestions.get(attemptId);
            QuestionAttemptStore.HistorySnapshot snapshot = snapshots.get(attemptId);
            JsonNode question = null;
            if (snapshot != null && "graded".equals(snapshot.status())
                    && questionId.equals(snapshot.questionId()) && snapshot.question() != null) {
                question = snapshot.question();
            } else if (snapshot == null || ("graded".equals(snapshot.status())
                    && questionId.equals(snapshot.questionId()) && snapshot.question() == null)) {
                QuestionDto current = fallbackQuestions.get(questionId);
                if (current != null) question = mapper.valueToTree(current);
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
