package cn.tihaishitu.world;

import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.game.*;
import cn.tihaishitu.learner.LearnerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/worlds")
public class WorldController {
    private final WorldCatalogService catalog;
    private final AncientOfficialWorldService world;
    private final GameActionService actions;

    public WorldController(WorldCatalogService catalog, AncientOfficialWorldService world, GameActionService actions) {
        this.catalog = catalog; this.world = world; this.actions = actions;
    }

    @GetMapping public List<WorldRegistry.WorldDefinition> worlds() { return catalog.forLearner(LearnerContext.learnerId()); }
    @GetMapping("/ancient-official") public ObjectNode get() { return world.get(); }
    @PostMapping("/ancient-official/initialize") @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode initialize(@RequestBody AncientOfficialWorldService.InitializeRequest request) { return world.initialize(request); }

    @PostMapping("/ancient-official/travel") public ObjectNode travel(@RequestBody JsonNode body) {
        return inWorld(() -> actions.travel(WorldRegistry.ANCIENT_OFFICIAL, required(body, "locationId")));
    }
    @PostMapping("/ancient-official/talk") public ObjectNode talk(@RequestBody JsonNode body) {
        return inWorld(() -> actions.talk(WorldRegistry.ANCIENT_OFFICIAL, required(body, "npcId"), required(body, "topicId")));
    }
    @PostMapping("/ancient-official/exams/register") public ObjectNode registerExam(@RequestBody JsonNode body) {
        return inWorld(() -> actions.registerExam(WorldRegistry.ANCIENT_OFFICIAL, required(body, "examId")));
    }
    @PostMapping("/ancient-official/activities") public ObjectNode begin(@RequestBody JsonNode body) {
        return inWorld(() -> actions.beginActivity(WorldRegistry.ANCIENT_OFFICIAL, required(body, "activityId")));
    }
    @PostMapping("/ancient-official/activities/finish") public ObjectNode finish(@RequestBody JsonNode body) {
        return inWorld(() -> actions.finish(WorldRegistry.ANCIENT_OFFICIAL, required(body, "runId")));
    }
    @PostMapping("/ancient-official/activities/abandon") public ObjectNode abandon(@RequestBody JsonNode body) {
        return inWorld(() -> actions.abandon(WorldRegistry.ANCIENT_OFFICIAL, required(body, "runId")));
    }
    @PostMapping("/ancient-official/answers") public ObjectNode answer(@Valid @RequestBody AnswerRequest body) {
        return inWorld(() -> actions.answer(WorldRegistry.ANCIENT_OFFICIAL, body));
    }
    @PostMapping("/ancient-official/answers/no-idea") public ObjectNode noIdea(@RequestBody JsonNode body) {
        return inWorld(() -> actions.noIdea(WorldRegistry.ANCIENT_OFFICIAL,
                required(body, "attemptId"), required(body, "questionId")));
    }
    @PostMapping("/ancient-official/answers/reveal") public ObjectNode reveal(@RequestBody JsonNode body) {
        return inWorld(() -> actions.reveal(WorldRegistry.ANCIENT_OFFICIAL, required(body, "attemptId"), required(body, "questionId")));
    }
    @PostMapping("/ancient-official/answers/self-assess") public ObjectNode assess(@Valid @RequestBody SelfAssessmentRequest body) {
        return inWorld(() -> actions.selfAssess(WorldRegistry.ANCIENT_OFFICIAL, body));
    }
    @PostMapping("/ancient-official/next") public ObjectNode next(@Valid @RequestBody NextQuestionRequest body) {
        return inWorld(() -> actions.next(WorldRegistry.ANCIENT_OFFICIAL, body));
    }
    @DeleteMapping("/ancient-official/encounter") public ObjectNode dismiss() {
        return inWorld(() -> actions.dismissEncounter(WorldRegistry.ANCIENT_OFFICIAL));
    }
    @PostMapping("/ancient-official/items/use") public ObjectNode use(@RequestBody JsonNode body) {
        return inWorld(() -> actions.useItem(WorldRegistry.ANCIENT_OFFICIAL, required(body, "itemId")));
    }
    @PostMapping("/ancient-official/items/buy") public ObjectNode buy(@RequestBody JsonNode body) {
        return inWorld(() -> actions.buyItem(WorldRegistry.ANCIENT_OFFICIAL, required(body, "itemId")));
    }
    @PutMapping("/ancient-official/notes") public ObjectNode note(@RequestBody JsonNode body) {
        return inWorld(() -> actions.saveNote(WorldRegistry.ANCIENT_OFFICIAL, required(body, "questionId"), body.path("note").asText()));
    }
    @PostMapping("/ancient-official/chapter") public ObjectNode chapter(@RequestBody JsonNode body) {
        return inWorld(() -> actions.acknowledgeChapter(WorldRegistry.ANCIENT_OFFICIAL, required(body, "chapterId")));
    }
    @PostMapping("/ancient-official/bonds") public ObjectNode bond(@RequestBody JsonNode body) {
        return inWorld(() -> actions.claimBond(WorldRegistry.ANCIENT_OFFICIAL, required(body, "npcId"), body.path("milestone").asInt(-1)));
    }
    @PostMapping("/ancient-official/choices") public ObjectNode choose(@RequestBody JsonNode body) {
        return inWorld(() -> actions.choose(WorldRegistry.ANCIENT_OFFICIAL, required(body, "eventId"), required(body, "choiceId")));
    }

    private ObjectNode inWorld(Supplier<ObjectNode> work) {
        return WorldActionContext.run(LearnerContext.learnerId(), WorldRegistry.ANCIENT_OFFICIAL, work);
    }
    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText();
        if (value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, field + " 不能为空。");
        return value;
    }
}
