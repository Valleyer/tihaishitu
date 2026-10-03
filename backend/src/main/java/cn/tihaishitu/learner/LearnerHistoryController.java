package cn.tihaishitu.learner;

import cn.tihaishitu.game.HistoryQuestionService;
import cn.tihaishitu.game.HistoryQuestionsRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learner/history")
public class LearnerHistoryController {
    private final HistoryQuestionService questions;
    public LearnerHistoryController(HistoryQuestionService questions) { this.questions = questions; }
    @PostMapping("/questions") public ObjectNode questions(@Valid @RequestBody HistoryQuestionsRequest request) {
        return questions.recoverForLearner(request);
    }
}
