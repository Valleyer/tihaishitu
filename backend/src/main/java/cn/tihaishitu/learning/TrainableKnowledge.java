package cn.tihaishitu.learning;

/**
 * 可作为正式学习目标的知识点 SQL 片段。
 *
 * <p>只保留“题量”这一条可玩条件：知识点必须与至少一道 published Formal Parent Question
 * 有关系。core 与 auxiliary 都算覆盖，且不再检查依赖知识点是否 ready，也不再检查
 * 今日是否已经答对或复习是否到期——能不能练与能不能获得 Mastery 奖励已经分离。</p>
 */
public final class TrainableKnowledge {
    private TrainableKnowledge() {}

    public static String exists(String knowledgeAlias) {
        return KnowledgeQuestionCoveragePolicy.exists(knowledgeAlias);
    }
}
