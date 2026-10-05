package cn.tihaishitu.bootstrap;

import cn.tihaishitu.catalog.CatalogService;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.StudyProfileService;
import cn.tihaishitu.manage.ManageUserStore;
import cn.tihaishitu.world.WorldCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BootstrapController {
    private final CatalogService catalog;
    private final StudyProfileService studyProfiles;
    private final WorldCatalogService worlds;
    private final ManageUserStore users;

    public BootstrapController(CatalogService catalog, StudyProfileService studyProfiles,
                               WorldCatalogService worlds, ManageUserStore users) {
        this.catalog = catalog;
        this.studyProfiles = studyProfiles;
        this.worlds = worlds;
        this.users = users;
    }

    @GetMapping("/bootstrap")
    BootstrapResponse bootstrap() {
        return new BootstrapResponse(
                LearnerContext.current(),
                !users.roles(LearnerContext.learnerId()).isEmpty(),
                studyProfiles.current(),
                worlds.forLearner(LearnerContext.learnerId()),
                catalog.findManifests().stream().filter(cn.tihaishitu.catalog.QuestionBankManifest::enabled).toList(),
                catalog.descriptor()
        );
    }
}
