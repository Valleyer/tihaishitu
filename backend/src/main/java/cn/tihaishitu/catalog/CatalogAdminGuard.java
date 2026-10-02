package cn.tihaishitu.catalog;

import cn.tihaishitu.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class CatalogAdminGuard {
    private final String configuredKey;

    public CatalogAdminGuard(@Value("${app.catalog.admin-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey;
    }

    public void require(String suppliedKey) {
        if (configuredKey.isBlank())
            throw new ApiException(HttpStatus.NOT_FOUND, "题库管理接口未启用。");
        byte[] expected = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] actual = (suppliedKey == null ? "" : suppliedKey).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "题库管理密钥不正确。");
    }
}
