package cn.tihaishitu.bootstrap;

import cn.tihaishitu.catalog.CatalogService;
import cn.tihaishitu.game.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BootstrapController {
    private final CatalogService catalog;
    private final GameService games;

    public BootstrapController(CatalogService catalog, GameService games) {
        this.catalog = catalog;
        this.games = games;
    }

    @GetMapping("/bootstrap")
    BootstrapResponse bootstrap() {
        return new BootstrapResponse(
                games.summaries(),
                catalog.findManifests(),
                catalog.descriptor(),
                games.latestId(),
                false
        );
    }
}
