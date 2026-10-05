package cn.tihaishitu.manage;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class BookManagementService {
    public record BookUpdate(String name, String description, boolean enabled, long expectedRevision) {}
    public record ChapterUpdate(String name, String description, long expectedRevision) {}

    private final BookManagementStore books;
    private final KnowledgeManagementStore knowledge;

    public BookManagementService(BookManagementStore books, KnowledgeManagementStore knowledge) {
        this.books = books;
        this.knowledge = knowledge;
    }

    public java.util.List<BookManagementStore.BookView> findAll() { return books.findAll(); }

    public BookManagementStore.BookDetail detail(String id) {
        return books.detail(id).orElseThrow(() -> missing("文集不存在。"));
    }

    @Transactional
    public BookManagementStore.BookDetail update(String id, BookUpdate request, Authentication auth) {
        if (request == null || blank(request.name())) bad("文集名称不能为空。");
        BookManagementStore.BookView before = books.find(id).orElseThrow(() -> missing("文集不存在。"));
        if (books.update(id, request.name().trim(), value(request.description()),
                request.enabled(), request.expectedRevision()) != 1) conflict(id);
        knowledge.audit(actor(auth), "BOOK_UPDATED", "question_bank", id, Map.of(
                "nameBefore", before.name(), "nameAfter", request.name().trim(),
                "enabledBefore", before.enabled(), "enabledAfter", request.enabled()));
        return detail(id);
    }

    @Transactional
    public BookManagementStore.ChapterView updateChapter(String bookId, String chapterId,
                                                           ChapterUpdate request, Authentication auth) {
        if (request == null || blank(request.name())) bad("章节名称不能为空。");
        BookManagementStore.BookDetail before = detail(bookId);
        BookManagementStore.ChapterView chapter = before.chapters().stream()
                .filter(item -> item.id().equals(chapterId)).findFirst()
                .orElseThrow(() -> missing("章节不存在。"));
        if (books.updateChapter(bookId, chapterId, request.name().trim(), value(request.description()),
                request.expectedRevision()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "章节已被其他人修改，请重新加载。");
        }
        knowledge.audit(actor(auth), "BOOK_CHAPTER_UPDATED", "question_bank_chapter", chapterId, Map.of(
                "bookId", bookId, "chapterCode", chapter.code(),
                "nameBefore", chapter.name(), "nameAfter", request.name().trim()));
        return detail(bookId).chapters().stream().filter(item -> item.id().equals(chapterId)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(String id, Authentication auth) {
        BookManagementStore.BookView before = books.find(id).orElseThrow(() -> missing("文集不存在。"));
        if (books.delete(id) != 1) throw missing("文集不存在。");
        knowledge.audit(actor(auth), "BOOK_DELETED", "question_bank", id, Map.of(
                "bookId", id, "bookName", before.name(), "chapterCount", before.chapterCount(),
                "membershipCount", before.membershipCount(),
                "selectedLearnerCount", before.selectedLearnerCount()));
    }

    private String actor(Authentication auth) { return knowledge.userId(auth.getName()); }
    private void conflict(String id) {
        if (books.find(id).isEmpty()) throw missing("文集不存在。");
        throw new ResponseStatusException(HttpStatus.CONFLICT, "文集已被其他人修改，请重新加载。");
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
