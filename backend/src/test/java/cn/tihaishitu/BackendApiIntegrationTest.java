package cn.tihaishitu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BackendApiIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void bootstrapDeclaresServerCatalog() throws Exception {
        mvc.perform(get("/api/v1/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionCatalog.source").value("server"))
                .andExpect(jsonPath("$.questionCatalog.canEdit").value(false))
                .andExpect(jsonPath("$.saves").isArray())
                .andExpect(jsonPath("$.bankManifest").isArray());
    }

    @Test
    void gameCanBeCreatedReadAndDeletedWithoutQuestionBanks() throws Exception {
        String response = mvc.perform(post("/api/v1/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"折叶",
                                  "gender":"男",
                                  "bankIds":[],
                                  "weights":{}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.player.name").value("折叶"))
                .andExpect(jsonPath("$.adventure.locationId").value("old-school"))
                .andExpect(jsonPath("$.attempt").isEmpty())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(response).path("id").asText();
        java.util.UUID.fromString(id);
        mvc.perform(get("/api/v1/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(delete("/api/v1/games/{id}", id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/games/{id}", id))
                .andExpect(status().isNotFound());
    }
}
