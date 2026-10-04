package cn.tihaishitu.learner;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learner/study-profile")
public class StudyProfileController {
    private final StudyProfileService service;
    public StudyProfileController(StudyProfileService service) { this.service = service; }

    @GetMapping public StudyProfileService.StudyProfileResponse get() { return service.current(); }
    @PutMapping public StudyProfileService.StudyProfileResponse update(
            @RequestBody StudyProfileService.UpdateStudyProfileRequest request) { return service.update(request); }
}
