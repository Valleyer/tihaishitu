package cn.tihaishitu.catalog;

public record BankMetadataRequest(
        String name,
        String description,
        Boolean enabled,
        Integer weight
) {}
