package cn.tihaishitu.catalog;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CatalogService {
    private final CatalogStore store;
    private final CatalogNormalizer normalizer;

    public CatalogService(CatalogStore store, CatalogNormalizer normalizer) {
        this.store = store;
        this.normalizer = normalizer;
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
        store.replaceAll(banks.stream().map(normalizer::normalize).toList());
    }
}
