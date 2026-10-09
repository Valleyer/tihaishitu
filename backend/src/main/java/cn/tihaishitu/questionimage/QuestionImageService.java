package cn.tihaishitu.questionimage;

import cn.tihaishitu.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class QuestionImageService {
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public record UploadResult(String id, String url, String originalName, String contentType, long byteSize) {}
    public record Download(QuestionImageStore.Asset asset, Resource resource) {}

    private final QuestionImageStore store;
    private final Path storageDir;

    public QuestionImageService(QuestionImageStore store,
                                @Value("${app.question-images.storage-dir:./data/question-images}") String storageDir) {
        this.store = store;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public UploadResult upload(MultipartFile file, String actorId) {
        if (file == null || file.isEmpty()) throw bad("请选择 PNG 或 JPEG 图片。");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片读取失败，请重试。");
        }
        if (bytes.length > MAX_BYTES) throw tooLarge();
        ImageType type = detect(bytes);
        String id = UUID.randomUUID().toString();
        String storageName = id + type.extension;
        Path target = storageDir.resolve(storageName).normalize();
        if (!target.getParent().equals(storageDir)) throw bad("图片存储路径不合法。");
        Path temporary = null;
        try {
            Files.createDirectories(storageDir);
            temporary = Files.createTempFile(storageDir, ".upload-", ".tmp");
            Files.write(temporary, bytes);
            move(temporary, target);
            temporary = null;
            QuestionImageStore.Asset asset = new QuestionImageStore.Asset(id, storageName,
                    cleanOriginalName(file.getOriginalFilename()), type.contentType, bytes.length,
                    sha256(bytes), actorId, Instant.now());
            try {
                store.insert(asset);
            } catch (RuntimeException error) {
                Files.deleteIfExists(target);
                throw error;
            }
            return new UploadResult(id, asset.url(), asset.originalName(), asset.contentType(), asset.byteSize());
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败，请稍后重试。");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public Download download(String id) {
        QuestionImageStore.Asset asset = store.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "题目图片不存在。"));
        Path path = storageDir.resolve(asset.storageName()).normalize();
        if (!path.getParent().equals(storageDir) || !Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "题目图片文件不存在。");
        }
        return new Download(asset, new FileSystemResource(path));
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static ImageType detect(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return new ImageType("image/png", ".png");
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff) {
            return new ImageType("image/jpeg", ".jpg");
        }
        throw bad("只允许上传 PNG 或 JPEG 图片。");
    }

    /**
     * original_name 只是 metadata，永远不参与磁盘路径，因此只取最后一段并做保守裁剪。
     *
     * <p>客户端可以提交含 NUL 等非法字符的文件名，{@code Path.of} 会抛
     * {@link java.nio.file.InvalidPathException}。这类字符先剔除再取文件名，
     * 不能让一个纯展示字段把上传变成 500。</p>
     */
    private static String cleanOriginalName(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.replace('\u0000', '_');
        String cleaned;
        try {
            Path name = Path.of(candidate).getFileName();
            cleaned = name == null ? "" : name.toString();
        } catch (InvalidPathException fallback) {
            int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
            cleaned = separator < 0 ? candidate : candidate.substring(separator + 1);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() <= 255 ? cleaned : cleaned.substring(cleaned.length() - 255);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5MB。"); }
    private record ImageType(String contentType, String extension) {}
}
