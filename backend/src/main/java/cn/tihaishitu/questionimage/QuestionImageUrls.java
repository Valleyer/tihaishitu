package cn.tihaishitu.questionimage;

public final class QuestionImageUrls {
    private QuestionImageUrls() {}

    public static String url(String assetId) {
        return assetId == null || assetId.isBlank() ? null : "/api/v1/question-images/" + assetId;
    }
}
