package cn.tihaishitu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.catalog.admin-key=test-secret",
        "app.catalog.seed-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:catalog-admin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class CatalogAdminIntegrationTest {
    private static final String BANK_ID = "11111111-1111-4111-8111-111111111111";
    private static final String POINT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String QUESTION_ID = "33333333-3333-4333-8333-333333333333";

    @Autowired
    MockMvc mvc;

    @Test
    void importRequiresKeyAndMetadataRenameBumpsRevision() throws Exception {
        String body = """
                {
                  "id":"%s",
                  "name":"导入测试卷",
                  "description":"由管理接口导入",
                  "enabled":true,
                  "weight":7,
                  "knowledgePoints":[{
                    "id":"%s","name":"一元函数极值点判定","subject":"数学",
                    "category":"高等数学","description":"判定极值点","explanation":"先求导。",
                    "prerequisites":[],"tags":["极值"]
                  }],
                  "questions":[{
                    "id":"%s","subject":"数学","category":"高等数学","chapter":"导数",
                    "type":"single_choice","question":"函数在何处可能取得极值？",
                    "options":{"A":"驻点","B":"任意点"},"answer":"A","explanation":"先检查驻点。",
                    "aliases":[],"keywords":[],"difficulty":1,"frequency":3,"tags":[],
                    "knowledgePointIds":["%s"],"enabled":true
                  }]
                }
                """.formatted(BANK_ID, POINT_ID, QUESTION_ID, POINT_ID);

        mvc.perform(post("/api/v1/admin/question-banks/import")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/admin/question-banks/import")
                        .header("X-Admin-Key", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(BANK_ID))
                .andExpect(jsonPath("$.questions[0].id").value(QUESTION_ID));

        mvc.perform(put("/api/v1/admin/question-banks/{id}/metadata", BANK_ID)
                        .header("X-Admin-Key", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"府试数学卷\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("府试数学卷"))
                .andExpect(jsonPath("$.weight").value(7))
                .andExpect(jsonPath("$.enabled").value(true));

        mvc.perform(get("/api/v1/bootstrap").cookie(register("catalog_admin_viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankManifest[?(@.id == '" + BANK_ID + "')].revision")
                        .value(org.hamcrest.Matchers.contains(2)));
    }

    @Test
    void invalidKnowledgePointLinksAreRejectedAtomically() throws Exception {
        String invalid = """
                {
                  "id":"44444444-4444-4444-8444-444444444444",
                  "name":"坏卷","description":"","enabled":true,"weight":1,
                  "knowledgePoints":[],
                  "questions":[]
                }
                """;
        mvc.perform(post("/api/v1/admin/question-banks/import")
                        .header("X-Admin-Key", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"测试\",\"password\":\"password-123\"}"))
                .andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}
