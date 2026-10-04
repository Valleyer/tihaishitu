package cn.tihaishitu.manage;

import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeManagementService {
    private final KnowledgeManagementStore store;
    private final LearnerKnowledgeStateService knowledgeStates;

    public KnowledgeManagementService(KnowledgeManagementStore store,
                                      LearnerKnowledgeStateService knowledgeStates) {
        this.store = store;
        this.knowledgeStates = knowledgeStates;
    }

    @Transactional
    public KnowledgeManagementStore.KnowledgeMergeResult merge(
            String sourceId, String targetId, long expectedRevision, String reason, String actorId) {
        knowledgeStates.mergeKnowledge(sourceId, targetId);
        return store.merge(sourceId, targetId, expectedRevision, reason, actorId);
    }
}
