package cn.tihaishitu.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class CatalogSeedLoader {
    private static final Logger log = LoggerFactory.getLogger(CatalogSeedLoader.class);

    private final CatalogService service;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Resource seed;

    public CatalogSeedLoader(
            CatalogService service,
            ObjectMapper objectMapper,
            @Value("${app.catalog.seed-enabled:true}") boolean enabled,
            @Value("${app.catalog.seed-location:classpath:content/question-banks.json}") Resource seed
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.seed = seed;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void seedEmptyCatalog() throws IOException {
        if (!enabled || !service.isEmpty()) return;
        List<QuestionBankDto> banks;
        try (var input = seed.getInputStream()) {
            banks = objectMapper.readValue(input, new TypeReference<>() {});
        }
        service.replaceAll(banks);
        log.info("题库为空，已导入 {} 部内置文集。", banks.size());
    }
}
