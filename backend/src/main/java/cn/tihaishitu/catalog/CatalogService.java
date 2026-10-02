package cn.tihaishitu.catalog;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CatalogService {
    private final CatalogStore store;
    private final CatalogNormalizer normalizer;
    private final CatalogValidator validator;

    public CatalogService(CatalogStore store, CatalogNormalizer normalizer, CatalogValidator validator) {
        this.store = store;
        this.normalizer = normalizer;
        this.validator = validator;
    }

    public List<QuestionBankDto> findAll() {
        return store.findAll();
    }

    public List<QuestionBankManifest> findManifests() {
        return store.findManifests();
    }

    public Optional<QuestionBankDto> findById(String id) {
        return store.findById(id);
    }

    public long revisionOf(String id) {
        return store.revisionOf(id);
    }

    public QuestionCatalogDescriptor descriptor() {
        return QuestionCatalogDescriptor.server(store.catalogRevision());
    }

    public boolean isEmpty() {
        return store.countBanks() == 0;
    }

    public void replaceAll(List<QuestionBankDto> banks) {
        var normalized = banks.stream().map(normalizer::normalize).toList();
        normalized.forEach(validator::validate);
        store.replaceAll(normalized);
    }

    public QuestionBankDto importBank(QuestionBankDto bank) {
        QuestionBankDto normalized = normalizer.normalize(bank);
        validator.validate(normalized);
        store.upsert(normalized);
        return findById(normalized.id()).orElseThrow();
    }

    public QuestionBankDto updateMetadata(String id, BankMetadataRequest request) {
        validator.validateId(id, "文集");
        QuestionBankDto current = findById(id).orElseThrow(() ->
                new cn.tihaishitu.common.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "没有找到这部文集。"));
        String name = request.name() == null ? current.name() : request.name().trim();
        if (name.isBlank()) throw validator.bad("文集名称不能为空。");
        int weight = request.weight() == null ? current.weight() : request.weight();
        if (weight < 0 || weight > 100)
            throw validator.bad("文集抽取权重必须在 0–100 之间。");
        String description = request.description() == null ? current.description() : request.description().trim();
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        if (!store.updateMetadata(id, name, description, enabled, weight))
            throw new cn.tihaishitu.common.ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "没有找到这部文集。");
        return findById(id).orElseThrow();
    }
}
