package cn.tihaishitu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-seed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.seed-enabled=true"
})
@AutoConfigureMockMvc
class CatalogSeedIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void builtInCatalogIsSeededWithUuidKeysAndAvailableByManifest() throws Exception {
        String body = mvc.perform(get("/api/v1/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankManifest").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("bankManifest").path(0).path("id").asText();
        UUID.fromString(id);
        mvc.perform(get("/api/v1/question-banks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.questions").isNotEmpty())
                .andExpect(jsonPath("$.knowledgePoints").isNotEmpty());
    }
}
