package cn.tihaishitu.bootstrap;

import cn.tihaishitu.catalog.QuestionBankManifest;
import cn.tihaishitu.catalog.QuestionCatalogDescriptor;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.StudyProfileService;
import cn.tihaishitu.world.WorldRegistry;

import java.util.List;

public record BootstrapResponse(
        LearnerContext.LearnerPrincipal learner,
        StudyProfileService.StudyProfileResponse studyProfile,
        List<WorldRegistry.WorldDefinition> worlds,
        List<QuestionBankManifest> bankManifest,
        QuestionCatalogDescriptor questionCatalog
) {}
