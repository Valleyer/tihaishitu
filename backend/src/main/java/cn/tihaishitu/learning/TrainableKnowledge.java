package cn.tihaishitu.learning;

/** Shared SQL fragments for knowledge points that can be used as formal learner targets. */
public final class TrainableKnowledge {
    private TrainableKnowledge() {}

    public static String exists(String knowledgeAlias) {
        return """
                EXISTS (
                    SELECT 1
                      FROM question_resource_knowledge trainable_rel
                      JOIN question_resource trainable_q ON trainable_q.id = trainable_rel.question_id
                     WHERE trainable_rel.knowledge_point_id = %s.id
                       AND trainable_rel.relation_role = 'core'
                       AND trainable_q.status = 'published'
                       AND trainable_q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                )
                """.formatted(knowledgeAlias).trim();
    }
}
