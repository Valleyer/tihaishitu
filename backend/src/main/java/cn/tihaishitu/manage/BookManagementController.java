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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookManagementStore.BookDetail create(
            @RequestBody BookManagementService.BookCreate request, Authentication auth) {
        return service.create(request, auth);
    }
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
    @PostMapping("/{bookId}/chapters")
    @ResponseStatus(HttpStatus.CREATED)
    public BookManagementStore.ChapterView createChapter(
            @PathVariable String bookId, @RequestBody BookManagementService.ChapterCreate request,
            Authentication auth) {
        return service.createChapter(bookId, request, auth);
    }
    @DeleteMapping("/{bookId}/chapters/{chapterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChapter(@PathVariable String bookId, @PathVariable String chapterId,
                              Authentication auth) {
        service.deleteChapter(bookId, chapterId, auth);
    }
    @PostMapping("/{bookId}/chapters/reorder")
    public List<BookManagementStore.ChapterView> reorderChapters(
            @PathVariable String bookId, @RequestBody BookManagementService.ChapterReorder request,
            Authentication auth) {
        return service.reorderChapters(bookId, request, auth);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, Authentication auth) { service.delete(id, auth); }
}
