package cn.tihaishitu.manage;

import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeManagementService {
    public record BlockedKnowledge(String id, String name, String reason) {}
    public record BulkDeleteResult(int deleted, List<BlockedKnowledge> blocked) {}
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
        KnowledgeManagementStore.KnowledgeMergeResult result =
                store.merge(sourceId, targetId, expectedRevision, reason, actorId);
        knowledgeStates.rebuildCoverage(targetId);
        return result;
    }

    @Transactional
    public BulkDeleteResult bulkDelete(List<String> requestedIds, String actorId) {
        List<BlockedKnowledge> blocked = new ArrayList<>();
        int deleted = 0;
        for (String id : new LinkedHashSet<>(requestedIds)) {
            if (id == null || id.isBlank()) continue;
            if (!store.lockForDelete(id)) {
                blocked.add(new BlockedKnowledge(id, "未知知识点", "知识点不存在"));
                continue;
            }
            var point = store.find(id).orElse(null);
            if (point == null) {
                blocked.add(new BlockedKnowledge(id, "未知知识点", "知识点不存在"));
                continue;
            }
            String reason = store.deleteBlockedReason(id);
            if (reason != null) {
                blocked.add(new BlockedKnowledge(id, point.name(), reason));
                continue;
            }
            if (store.deleteOrphan(id) == 1) {
                deleted++;
                store.audit(actorId, "KNOWLEDGE_DELETED", "knowledge_point", id,
                        Map.of("code", point.code(), "name", point.name()));
            } else {
                blocked.add(new BlockedKnowledge(id, point.name(), "删除时知识点状态已变化"));
            }
        }
        return new BulkDeleteResult(deleted, List.copyOf(blocked));
    }
}
