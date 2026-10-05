package cn.tihaishitu.manage;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/manage/books")
@PreAuthorize("hasRole('ADMIN')")
public class BookManagementController {
    private final BookManagementService service;

    public BookManagementController(BookManagementService service) { this.service = service; }

    @GetMapping public List<BookManagementStore.BookView> all() { return service.findAll(); }
    @GetMapping("/{id}") public BookManagementStore.BookDetail detail(@PathVariable String id) {
        return service.detail(id);
    }
    @PutMapping("/{id}") public BookManagementStore.BookDetail update(
            @PathVariable String id, @RequestBody BookManagementService.BookUpdate request, Authentication auth) {
        return service.update(id, request, auth);
    }
    @PutMapping("/{bookId}/chapters/{chapterId}")
    public BookManagementStore.ChapterView updateChapter(
            @PathVariable String bookId, @PathVariable String chapterId,
            @RequestBody BookManagementService.ChapterUpdate request, Authentication auth) {
        return service.updateChapter(bookId, chapterId, request, auth);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, Authentication auth) { service.delete(id, auth); }
}
