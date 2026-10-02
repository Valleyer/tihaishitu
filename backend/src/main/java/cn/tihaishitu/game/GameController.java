package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/games")
public class GameController {
    private final GameService service;
    private final GameActionService actions;

    public GameController(GameService service, GameActionService actions) {
        this.service = service;
        this.actions = actions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode create(@Valid @RequestBody NewGameRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    JsonNode find(@PathVariable String id) {
        return service.find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) {
        service.delete(id);
    }

    @PostMapping("/{id}/travel")
    JsonNode travel(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.travel(id, required(body, "locationId"));
    }

    @PostMapping("/{id}/talk")
    JsonNode talk(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.talk(id, required(body, "npcId"), required(body, "topicId"));
    }

    @PostMapping("/{id}/exams/register")
    JsonNode registerExam(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.registerExam(id, required(body, "examId"));
    }

    @PostMapping("/{id}/activities")
    JsonNode beginActivity(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.beginActivity(id, required(body, "activityId"));
    }

    @PostMapping("/{id}/activities/finish")
    JsonNode finishActivity(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.finish(id, required(body, "runId"));
    }

    @PostMapping("/{id}/activities/abandon")
    JsonNode abandonActivity(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.abandon(id, required(body, "runId"));
    }

    @PostMapping("/{id}/answers")
    JsonNode answer(@PathVariable String id, @Valid @RequestBody AnswerRequest body) {
        return actions.answer(id, body);
    }

    @PostMapping("/{id}/answers/reveal")
    JsonNode reveal(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.reveal(id, required(body, "attemptId"), required(body, "questionId"));
    }

    @PostMapping("/{id}/answers/self-assess")
    JsonNode selfAssess(@PathVariable String id, @Valid @RequestBody SelfAssessmentRequest body) {
        return actions.selfAssess(id, body);
    }

    @PostMapping("/{id}/next")
    JsonNode next(@PathVariable String id, @Valid @RequestBody NextQuestionRequest body) {
        return actions.next(id, body);
    }

    @DeleteMapping("/{id}/encounter")
    JsonNode dismissEncounter(@PathVariable String id) {
        return actions.dismissEncounter(id);
    }

    @PostMapping("/{id}/items/use")
    JsonNode useItem(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.useItem(id, required(body, "itemId"));
    }

    @PostMapping("/{id}/items/buy")
    JsonNode buyItem(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.buyItem(id, required(body, "itemId"));
    }

    @PutMapping("/{id}/notes")
    JsonNode saveNote(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.saveNote(id, required(body, "questionId"), body.path("note").asText());
    }

    @PutMapping("/{id}/configuration")
    JsonNode configure(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.configure(id, body);
    }

    @PostMapping("/{id}/chapter")
    JsonNode acknowledgeChapter(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.acknowledgeChapter(id, required(body, "chapterId"));
    }

    @PostMapping("/{id}/bonds")
    JsonNode claimBond(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.claimBond(id, required(body, "npcId"), body.path("milestone").asInt(-1));
    }

    @PostMapping("/{id}/choices")
    JsonNode choose(@PathVariable String id, @RequestBody JsonNode body) {
        return actions.choose(id, required(body, "eventId"), required(body, "choiceId"));
    }

    @GetMapping("/{id}/export")
    JsonNode exportSave(@PathVariable String id) {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(service.exportSave(id));
    }

    @PostMapping("/import")
    JsonNode importSave(@RequestBody JsonNode body) {
        return service.importSave(required(body, "json"));
    }

    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText();
        if (value.isBlank()) throw new cn.tihaishitu.common.ApiException(HttpStatus.BAD_REQUEST, field + " 不能为空。");
        return value;
    }
}
