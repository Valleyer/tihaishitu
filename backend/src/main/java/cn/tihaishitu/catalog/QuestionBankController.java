package cn.tihaishitu.catalog;

import cn.tihaishitu.common.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/question-banks")
public class QuestionBankController {
    private final CatalogService service;

    public QuestionBankController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    List<QuestionBankDto> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    ResponseEntity<QuestionBankDto> find(@PathVariable String id) {
        QuestionBankDto bank = service.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "没有找到这部文集。"));
        return ResponseEntity.ok()
                .eTag("\"bank-" + id + "-" + service.revisionOf(id) + "\"")
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(bank);
    }
}
