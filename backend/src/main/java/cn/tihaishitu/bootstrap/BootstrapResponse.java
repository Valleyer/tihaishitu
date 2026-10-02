package cn.tihaishitu.bootstrap;

import cn.tihaishitu.catalog.QuestionBankManifest;
import cn.tihaishitu.catalog.QuestionCatalogDescriptor;
import cn.tihaishitu.game.SaveSummaryDto;

import java.util.List;

public record BootstrapResponse(
        List<SaveSummaryDto> saves,
        List<QuestionBankManifest> bankManifest,
        QuestionCatalogDescriptor questionCatalog,
        String activeId,
        boolean legacyNotice
) {}
