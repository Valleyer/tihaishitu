package cn.tihaishitu;

import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.learner.LearnerAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR11 题干图片：immutable asset 主链路。
 *
 * <p>覆盖上传校验、按 asset id 读取、Question 绑定 contract、各正式题 projection，
 * 以及最关键的 frozen Attempt 语义（换图 / 移除图片都不得改变历史 Attempt）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:question-stem-image;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=platform-admin",
        "app.initial-admin.password=platform-admin-test-password",
})
class QuestionStemImageIntegrationTest {
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0,
    };
    private static final byte[] JPEG_BYTES = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1,
    };

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void imageStorage(DynamicPropertyRegistry registry) {
        registry.add("app.question-images.storage-dir", () -> imageDir.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired KnowledgeQuestionPoolService pool;

    @BeforeEach
    void resetState() throws Exception {
        ensureUser("image-contributor", "配图贡献者", "CONTRIBUTOR");
        ensureUser("image-publisher", "配图审核者", "REVIEWER");
        // 同一个 H2 内存库与同一个临时目录贯穿整个测试类，逐测试清空避免互相污染。
        // 先断开 / 删除所有指向 study_attempt 的引用（含 current_attempt_id 与掌握度证据）。
        jdbc.update("UPDATE learner_practice_session SET current_attempt_id=NULL");
        jdbc.update("DELETE FROM learner_knowledge_evidence");
        jdbc.update("DELETE FROM study_attempt");
        jdbc.update("DELETE FROM learner_practice_session");
        jdbc.update("DELETE FROM learner_wrong_question WHERE question_id IS NOT NULL");
        jdbc.update("DELETE FROM learner_question_mastery");
        jdbc.update("DELETE FROM question_resource_knowledge");
        jdbc.update("DELETE FROM question_resource_option");
        jdbc.update("UPDATE question_resource SET stem_image_id=NULL");
        jdbc.update("DELETE FROM question_resource");
        jdbc.update("DELETE FROM question_image_asset");
        for (String file : imageFiles()) Files.deleteIfExists(imageDir.resolve(file));
    }

    // ---------------------------------------------------------------- 上传校验

    @Test
    void pngUploadIsStoredAsAnImmutableAssetAndServedByAssetId() throws Exception {
        Cookie contributor = login("image-contributor");
        String assetId = upload(contributor, png("题干图.png"));

        assertThat(assetId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT content_type FROM question_image_asset WHERE id=?",
                String.class, assetId)).isEqualTo("image/png");
        assertThat(jdbc.queryForObject("SELECT original_name FROM question_image_asset WHERE id=?",
                String.class, assetId)).isEqualTo("题干图.png");
        assertThat(jdbc.queryForObject("SELECT byte_size FROM question_image_asset WHERE id=?",
                Long.class, assetId)).isEqualTo((long) PNG_BYTES.length);

        // 文件按 UUID storage name 落在配置目录，且内容与上传字节完全一致。
        String storageName = jdbc.queryForObject("SELECT storage_name FROM question_image_asset WHERE id=?",
                String.class, assetId);
        assertThat(storageName).isEqualTo(assetId + ".png");
        assertThat(Files.readAllBytes(imageDir.resolve(storageName))).isEqualTo(PNG_BYTES);

        mvc.perform(get("/api/v1/question-images/{id}", assetId).cookie(contributor))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG_BYTES))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    void jpegUploadIsAcceptedAndServedAsJpeg() throws Exception {
        Cookie contributor = login("image-contributor");
        String assetId = upload(contributor, jpeg("题干图.jpg"));

        assertThat(jdbc.queryForObject("SELECT content_type FROM question_image_asset WHERE id=?",
                String.class, assetId)).isEqualTo("image/jpeg");
        assertThat(jdbc.queryForObject("SELECT storage_name FROM question_image_asset WHERE id=?",
                String.class, assetId)).isEqualTo(assetId + ".jpg");

        mvc.perform(get("/api/v1/question-images/{id}", assetId).cookie(contributor))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(JPEG_BYTES));
    }

    @Test
    void nonImageBytesAreRejectedEvenWithAnImageExtension() throws Exception {
        Cookie contributor = login("image-contributor");
        MockMultipartFile fake = new MockMultipartFile("file", "伪装.png", "image/png",
                "this is definitely not an image".getBytes());

        mvc.perform(multipart("/api/v1/manage/question-images").file(fake).cookie(contributor).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("只允许上传 PNG 或 JPEG 图片。"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_image_asset", Integer.class)).isZero();
        assertThat(imageFiles()).isEmpty();
    }

    @Test
    void emptyUploadIsRejected() throws Exception {
        Cookie contributor = login("image-contributor");
        MockMultipartFile empty = new MockMultipartFile("file", "空.png", "image/png", new byte[0]);

        mvc.perform(multipart("/api/v1/manage/question-images").file(empty).cookie(contributor).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请选择 PNG 或 JPEG 图片。"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_image_asset", Integer.class)).isZero();
    }

    @Test
    void imagesLargerThanFiveMegabytesAreRejectedWithoutPersistingAFile() throws Exception {
        Cookie contributor = login("image-contributor");
        byte[] oversized = new byte[(int) (5L * 1024 * 1024) + 1];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        MockMultipartFile large = new MockMultipartFile("file", "超大.png", "image/png", oversized);

        // 5MB 有两条防线：multipart 上限（413）与 Service 自身的 MAX_BYTES 校验，都必须是 413 + 统一文案。
        mvc.perform(multipart("/api/v1/manage/question-images").file(large).cookie(contributor).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("图片不能超过 5MB。"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_image_asset", Integer.class)).isZero();
        assertThat(imageFiles()).isEmpty();
    }

    @Test
    void aFileNameWithPathSeparatorsIsReducedToItsBaseName() throws Exception {
        Cookie contributor = login("image-contributor");
        MockMultipartFile traversal = new MockMultipartFile("file", "../../evil/图.png", "image/png", PNG_BYTES);

        String body = mvc.perform(multipart("/api/v1/manage/question-images").file(traversal)
                        .cookie(contributor).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // original_name 只是 metadata；storage name 永远是 UUID，不能出现路径成分。
        JsonNode asset = mapper.readTree(body);
        assertThat(asset.path("originalName").asText()).isEqualTo("图.png");
        String storageName = jdbc.queryForObject("SELECT storage_name FROM question_image_asset WHERE id=?",
                String.class, asset.path("id").asText());
        assertThat(storageName).isEqualTo(asset.path("id").asText() + ".png");
        assertThat(imageDir.resolve(storageName).getParent()).isEqualTo(imageDir);
    }

    @Test
    void aFileNameWithIllegalPathCharactersStillUploadsInsteadOfFailingWith500() throws Exception {
        Cookie contributor = login("image-contributor");
        // NUL 等字符会让 Path.of 抛 InvalidPathException；original_name 只是 metadata，
        // 不能因为一个展示字段把上传变成 500。
        MockMultipartFile hostile = new MockMultipartFile("file", "a\u0000b/../../c.png", "image/png", PNG_BYTES);

        String body = mvc.perform(multipart("/api/v1/manage/question-images").file(hostile)
                        .cookie(contributor).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(body).path("id").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT original_name FROM question_image_asset", String.class))
                .doesNotContain("/").doesNotContain("\\");
        assertThat(imageFiles()).hasSize(1);
    }

    @Test
    void uploadRequiresALearnerRoleAndAssetDownloadRejectsAnUnknownId() throws Exception {
        // anyRequest().permitAll() 但 /api/v1/manage/** 需要 CONTRIBUTOR / REVIEWER / ADMIN。
        MockMultipartFile file = new MockMultipartFile("file", "图.png", "image/png", PNG_BYTES);
        mvc.perform(multipart("/api/v1/manage/question-images").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/question-images/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("题目图片不存在。"));
    }

    // ------------------------------------------------------ Question 绑定 contract

    @Test
    void aQuestionCanBindAnExistingAssetAndTheViewExposesBothIdAndUrl() throws Exception {
        Cookie reviewer = login("image-contributor");
        String assetId = upload(reviewer, png("绑定.png"));
        String point = scope("stem-bind").point();

        String created = createQuestion(reviewer, point, assetId);
        JsonNode question = mapper.readTree(created);
        assertThat(question.path("stemImageId").asText()).isEqualTo(assetId);
        assertThat(question.path("stemImageUrl").asText()).isEqualTo("/api/v1/question-images/" + assetId);

        mvc.perform(get("/api/v1/manage/questions/{id}", question.path("id").asText()).cookie(reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stemImageId").value(assetId))
                .andExpect(jsonPath("$.stemImageUrl").value("/api/v1/question-images/" + assetId));
    }

    @Test
    void bindingAnUnknownAssetIsRejectedOnCreateAndOnUpdate() throws Exception {
        Cookie reviewer = login("image-contributor");
        String point = scope("stem-unknown").point();
        String unknown = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/manage/questions").cookie(reviewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(point, null, unknown)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("题目引用了不存在的题干图片。"));

        // 已存在的题也不能被改成引用不存在的 asset。
        String created = createQuestion(reviewer, point, null);
        JsonNode question = mapper.readTree(created);
        assertThat(question.path("stemImageUrl").isNull() || question.path("stemImageUrl").asText().isEmpty())
                .isTrue();
        mvc.perform(put("/api/v1/manage/questions/{id}", question.path("id").asText())
                        .cookie(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(point, question.path("revision").asLong(), unknown)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("题目引用了不存在的题干图片。"));
    }

    @Test
    void aQuestionCanReplaceItsImageWithANewAssetAndThenRemoveItEntirely() throws Exception {
        Cookie reviewer = login("image-contributor");
        String point = scope("stem-replace").point();
        String firstAsset = upload(reviewer, png("第一张.png"));
        JsonNode question = mapper.readTree(createQuestion(reviewer, point, firstAsset));
        String questionId = question.path("id").asText();

        String secondAsset = upload(reviewer, jpeg("第二张.jpg"));
        String replaced = mvc.perform(put("/api/v1/manage/questions/{id}", questionId)
                        .cookie(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(point, question.path("revision").asLong(), secondAsset)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stemImageId").value(secondAsset))
                .andExpect(jsonPath("$.stemImageUrl").value("/api/v1/question-images/" + secondAsset))
                .andReturn().getResponse().getContentAsString();

        // 旧 asset 仍然是 immutable 历史资源，读取不能 404。
        mvc.perform(get("/api/v1/question-images/{id}", firstAsset).cookie(reviewer))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_image_asset", Integer.class)).isEqualTo(2);

        long revision = mapper.readTree(replaced).path("revision").asLong();
        mvc.perform(put("/api/v1/manage/questions/{id}", questionId)
                        .cookie(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(point, revision, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stemImageId").doesNotExist())
                .andExpect(jsonPath("$.stemImageUrl").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT stem_image_id FROM question_resource WHERE id=?",
                String.class, questionId)).isNull();
        // 移除绑定不得物理删除 asset（历史 Attempt 仍要能读图）。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_image_asset", Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------ 正式题 projection

    @Test
    void formalQuestionProjectionsCarryTheStemImageUrl() throws Exception {
        Cookie reviewer = login("image-contributor");
        Cookie publisher = login("image-publisher");
        String assetId = upload(reviewer, png("投影.png"));
        KnowledgeFixture fixture = scope("stem-projection");
        String point = fixture.point();
        JsonNode question = mapper.readTree(createQuestion(reviewer, point, assetId));
        String questionId = question.path("id").asText();
        publish(reviewer, publisher, questionId);
        String expectedUrl = "/api/v1/question-images/" + assetId;

        // World / RANDOM / Activity 共用的正式题 DTO。
        List<QuestionDto> worldPool = pool.questionsByIds(Set.of(questionId));
        assertThat(worldPool).hasSize(1);
        assertThat(worldPool.get(0).stemImageUrl()).isEqualTo(expectedUrl);

        Cookie learner = learnerFor("stem-projection", fixture);

        // 万境中枢题库浏览 projection（题干详情需要学习者会话）。
        mvc.perform(get("/api/v1/learning/questions/{id}", questionId).cookie(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stemImageUrl").value(expectedUrl));
        mvc.perform(get("/api/v1/learning/knowledge-points/{id}/questions", point).cookie(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stemImageUrl").value(expectedUrl));

        // 专项练习 Attempt 的题面。
        JsonNode session = startKnowledge(learner, point);
        assertThat(session.path("currentAttempt").path("question").path("stemImageUrl").asText())
                .isEqualTo(expectedUrl);
    }

    @Test
    void wrongBookProjectionCarriesTheStemImageUrl() throws Exception {
        Cookie reviewer = login("image-contributor");
        Cookie publisher = login("image-publisher");
        String assetId = upload(reviewer, png("错题.png"));
        KnowledgeFixture fixture = scope("stem-wrong");
        JsonNode question = mapper.readTree(createQuestion(reviewer, fixture.point(), assetId));
        String questionId = question.path("id").asText();
        publish(reviewer, publisher, questionId);

        Cookie learner = learnerFor("stem-wrong", fixture);
        JsonNode session = startKnowledge(learner, fixture.point());
        String sessionId = session.path("id").asText();
        String attemptId = session.path("currentAttempt").path("id").asText();
        answerWrongly(learner, sessionId, attemptId, questionId);

        mvc.perform(get("/api/v1/learner/wrong-questions").cookie(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questionId").value(questionId))
                .andExpect(jsonPath("$[0].stemImageUrl").value("/api/v1/question-images/" + assetId));
    }

    // -------------------------------------------------------------- frozen Attempt

    @Test
    void anExistingAttemptKeepsImageAWhileANewAttemptUsesImageBAfterTheQuestionIsRebound() throws Exception {
        Cookie reviewer = login("image-contributor");
        Cookie publisher = login("image-publisher");
        KnowledgeFixture fixture = scope("stem-freeze-swap");
        String point = fixture.point();
        String assetA = upload(reviewer, png("图A.png"));
        String assetB = upload(reviewer, jpeg("图B.jpg"));
        JsonNode question = mapper.readTree(createQuestion(reviewer, point, assetA));
        String questionId = question.path("id").asText();
        long revision = publish(reviewer, publisher, questionId);
        Cookie learner = learnerFor("stem-freeze-swap", fixture);

        // 旧 Attempt 冻结图 A。
        JsonNode oldSession = startKnowledge(learner, point);
        String oldSessionId = oldSession.path("id").asText();
        String oldAttemptId = oldSession.path("currentAttempt").path("id").asText();
        assertThat(oldSession.path("currentAttempt").path("question").path("stemImageUrl").asText())
                .isEqualTo("/api/v1/question-images/" + assetA);

        // 后台把题目改绑图 B。
        mvc.perform(put("/api/v1/manage/questions/{id}", questionId)
                        .cookie(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(point, revision, assetB)))
                .andExpect(status().isOk());

        // 旧 Attempt 恢复后仍是图 A：snapshot 不能被当前题目拖动。
        JsonNode restored = json(mvc.perform(get("/api/v1/learner/practice-sessions/{id}", oldSessionId)
                        .cookie(learner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("currentAttempt").path("id").asText()).isEqualTo(oldAttemptId);
        assertThat(restored.path("currentAttempt").path("question").path("stemImageUrl").asText())
                .isEqualTo("/api/v1/question-images/" + assetA);
        assertThat(mapper.readTree(jdbc.queryForObject(
                "SELECT question_snapshot_json FROM study_attempt WHERE id=?", String.class, oldAttemptId))
                .path("stemImageUrl").asText()).isEqualTo("/api/v1/question-images/" + assetA);

        // 新 Attempt 才是图 B。
        JsonNode newSession = startKnowledge(learner, point);
        assertThat(newSession.path("currentAttempt").path("question").path("stemImageUrl").asText())
                .isEqualTo("/api/v1/question-images/" + assetB);
    }

    @Test
    void anExistingAttemptKeepsItsImageAfterTheQuestionRemovesTheImageEntirely() throws Exception {
        Cookie reviewer = login("image-contributor");
        Cookie publisher = login("image-publisher");
        KnowledgeFixture fixture = scope("stem-freeze-remove");
        String point = fixture.point();
        String assetA = upload(reviewer, png("图A.png"));
        JsonNode question = mapper.readTree(createQuestion(reviewer, point, assetA));
        String questionId = question.path("id").asText();
        long revision = publish(reviewer, publisher, questionId);
        Cookie learner = learnerFor("stem-freeze-remove", fixture);

        JsonNode oldSession = startKnowledge(learner, point);
        String oldSessionId = oldSession.path("id").asText();
        String oldAttemptId = oldSession.path("currentAttempt").path("id").asText();

        // 当前 Question 移除图片。
        mvc.perform(put("/api/v1/manage/questions/{id}", questionId)
                        .cookie(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(point, revision, null)))
                .andExpect(status().isOk());

        // 旧 Attempt 仍保留旧图；新 Attempt 完全没有图（无 URL，前端零占位）。
        JsonNode restored = json(mvc.perform(get("/api/v1/learner/practice-sessions/{id}", oldSessionId)
                        .cookie(learner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("currentAttempt").path("question").path("stemImageUrl").asText())
                .isEqualTo("/api/v1/question-images/" + assetA);
        assertThat(mapper.readTree(jdbc.queryForObject(
                "SELECT question_snapshot_json FROM study_attempt WHERE id=?", String.class, oldAttemptId))
                .path("stemImageUrl").asText()).isEqualTo("/api/v1/question-images/" + assetA);

        JsonNode newSession = startKnowledge(learner, point);
        JsonNode newQuestion = newSession.path("currentAttempt").path("question");
        assertThat(newQuestion.path("stemImageUrl").isMissingNode() || newQuestion.path("stemImageUrl").isNull())
                .isTrue();
        // 旧 asset 依然可读，供历史 Attempt 展示。
        mvc.perform(get("/api/v1/question-images/{id}", assetA).cookie(learner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
    }

    // ------------------------------------------------------------------- helpers

    private List<String> imageFiles() throws Exception {
        try (var files = Files.list(imageDir)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG_BYTES);
    }

    private MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("file", name, "image/jpeg", JPEG_BYTES);
    }

    private String upload(Cookie actor, MockMultipartFile file) throws Exception {
        String body = mvc.perform(multipart("/api/v1/manage/question-images").file(file)
                        .cookie(actor).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode asset = mapper.readTree(body);
        assertThat(asset.path("url").asText()).isEqualTo("/api/v1/question-images/" + asset.path("id").asText());
        assertThat(asset.path("byteSize").asLong()).isEqualTo(file.getSize());
        return asset.path("id").asText();
    }

    /**
     * 建一道题可用的一整套目录上下文：文集 → 章节 → 知识点。
     *
     * <p>正式练习的发题范围按 {@code learner_selected_book → question_bank_knowledge}
     * 计算，所以知识点必须挂进一个文集，否则知识专项会以
     * “知识点不在当前可选文集范围内”拒绝创建 Session。</p>
     */
    private KnowledgeFixture scope(String prefix) {
        String book = UUID.randomUUID().toString();
        String chapter = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,'',TRUE,1,1)",
                book, prefix);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,'C','章','',0,1)
                """, chapter, book);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, prefix + "-" + point, prefix);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",
                book, point, chapter);
        return new KnowledgeFixture(book, point);
    }

    private record KnowledgeFixture(String book, String point) {}

    private String createQuestion(Cookie actor, String pointId, String stemImageId) throws Exception {
        return mvc.perform(post("/api/v1/manage/questions").cookie(actor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionBody(pointId, null, stemImageId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 正式题必须挂至少一个知识点才能进入正式题库；这里生成一份可发布的判断题请求体。 */
    private String questionBody(String pointId, Long expectedRevision, String stemImageId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subject", "数学一");
        body.put("sourceId", sourceId("配图测试来源"));
        body.put("sourceType", "custom");
        body.put("sourceName", "配图测试来源");
        body.put("questionNumber", "1");
        body.put("questionType", "true_false");
        body.put("presentationType", "true_false");
        body.put("gradingMode", "auto");
        body.put("content", "题干配图测试题。");
        body.put("analysis", "解析");
        body.put("difficulty", 2);
        body.put("stemImageId", stemImageId);
        body.put("options", List.of(
                Map.of("key", "true", "text", "正确", "correct", true, "sortOrder", 0),
                Map.of("key", "false", "text", "错误", "correct", false, "sortOrder", 1)));
        body.put("knowledgePoints", List.of(Map.of("knowledgePointId", pointId, "role", "core", "sortOrder", 0)));
        if (expectedRevision != null) body.put("expectedRevision", expectedRevision);
        return mapper.writeValueAsString(body);
    }

    /**
     * 提交并发布题目，返回发布后的 revision。
     *
     * <p>提交只能由创建者本人完成；审核又不能审核自己创建的题目（ADMIN 除外），
     * 所以这里固定用创建者提交、另一个审核账号发布。revision 直接读库，避免过期版本。</p>
     */
    private long publish(Cookie creator, Cookie publisher, String questionId) throws Exception {
        long current = jdbc.queryForObject("SELECT revision FROM question_resource WHERE id=?",
                Long.class, questionId);
        String submitted = mvc.perform(post("/api/v1/manage/questions/{id}/submit", questionId)
                        .cookie(creator).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + current + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andReturn().getResponse().getContentAsString();
        long pendingRevision = mapper.readTree(submitted).path("revision").asLong();
        String reviewed = mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .cookie(publisher).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + pendingRevision + ",\"approve\":true,\"comment\":\"配图审核通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(reviewed).path("revision").asLong();
    }

    private JsonNode startKnowledge(Cookie learner, String point) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(learner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"knowledge_drill\",\"targetKnowledgePointId\":\"%s\"}".formatted(point)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void answerWrongly(Cookie learner, String session, String attempt, String question) throws Exception {
        boolean frozenCorrect = mapper.readTree(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attempt)).asBoolean();
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", session)
                        .with(csrf()).cookie(learner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attempt, question, !frozenCorrect)))
                .andExpect(status().isOk());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"配图学习者\",\"password\":\"password-123\"}"
                                .formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    /**
     * 把文集设为学习者当前学习范围。
     *
     * <p>注册流程可能已经默认勾选了所有启用文集，因此先清空再写入，保证多次调用幂等。</p>
     */
    private void selectBook(String username, String bookId) {
        String learnerId = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?",
                String.class, username);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learnerId);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learnerId, bookId);
    }

    /** 注册学习者并立即把该文集设为当前学习范围。 */
    private Cookie learnerFor(String username, KnowledgeFixture fixture) throws Exception {
        Cookie learner = register(username);
        selectBook(username, fixture.book());
        return learner;
    }

    private Cookie login(String username) throws Exception {
        var result = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", username, "password", username + "-test-password"))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private void ensureUser(String username, String displayName, String role) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM learner_account WHERE username = ?",
                Integer.class, username);
        if (count != null && count > 0) return;
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id, username, display_name, password_hash, status) VALUES (?, ?, ?, ?, 'active')",
                id, username, displayName, encoder.encode(username + "-test-password"));
        jdbc.update("INSERT INTO learner_account_role(learner_id, role_name) VALUES (?, ?)", id, role);
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    /** 题目必须绑定一个 active 来源；测试里按需创建。 */
    private String sourceId(String name) {
        String id = jdbc.query("SELECT id FROM question_source WHERE canonical_name=?",
                result -> result.next() ? result.getString(1) : null, name);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision)
                VALUES (?,'custom',?,?,'active',1)
                """, id, name, name);
        return id;
    }
}
