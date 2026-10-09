package cn.tihaishitu.questionimage;

import cn.tihaishitu.manage.KnowledgeManagementStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class QuestionImageController {
    private final QuestionImageService images;
    private final KnowledgeManagementStore users;

    public QuestionImageController(QuestionImageService images, KnowledgeManagementStore users) {
        this.images = images;
        this.users = users;
    }

    @PostMapping(value = "/manage/question-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    QuestionImageService.UploadResult upload(@RequestParam("file") MultipartFile file, Authentication authentication) {
        return images.upload(file, users.userId(authentication.getName()));
    }

    @GetMapping("/question-images/{assetId}")
    ResponseEntity<org.springframework.core.io.Resource> get(@PathVariable String assetId) {
        QuestionImageService.Download download = images.download(assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.asset().contentType()))
                .contentLength(download.asset().byteSize())
                .eTag('"' + download.asset().sha256() + '"')
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(download.resource());
    }
}
