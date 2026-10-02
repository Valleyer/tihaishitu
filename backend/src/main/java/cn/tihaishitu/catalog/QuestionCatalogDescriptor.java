package cn.tihaishitu.catalog;

public record QuestionCatalogDescriptor(String source, boolean canEdit, String revision) {
    public static QuestionCatalogDescriptor server(long revision) {
        return new QuestionCatalogDescriptor("server", false, Long.toString(revision));
    }
}
