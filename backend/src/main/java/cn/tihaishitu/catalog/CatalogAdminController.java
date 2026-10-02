package cn.tihaishitu.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/question-banks")
public class CatalogAdminController {
    private final CatalogService service;
    private final CatalogAdminGuard guard;

    public CatalogAdminController(CatalogService service, CatalogAdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    QuestionBankDto importBank(
            @RequestHeader(name = "X-Admin-Key", required = false) String key,
            @RequestBody QuestionBankDto bank
    ) {
        guard.require(key);
        return service.importBank(bank);
    }

    @PutMapping("/{id}/metadata")
    QuestionBankDto updateMetadata(
            @RequestHeader(name = "X-Admin-Key", required = false) String key,
            @PathVariable String id,
            @RequestBody BankMetadataRequest request
    ) {
        guard.require(key);
        return service.updateMetadata(id, request);
    }
}
