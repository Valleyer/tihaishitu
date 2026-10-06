package cn.tihaishitu.learning;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(400)
public class QuestionCoverageStateMigration implements ApplicationRunner {
    private final LearnerKnowledgeStateService states;

    public QuestionCoverageStateMigration(LearnerKnowledgeStateService states) {
        this.states = states;
    }

    @Override
    public void run(ApplicationArguments args) {
        states.rebuildLegacyStates();
    }
}
