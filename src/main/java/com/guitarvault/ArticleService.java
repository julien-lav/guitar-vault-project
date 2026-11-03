package com.guitarvault;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ArticleService {
    public static final List<String> CATEGORIES = List.of("guides", "technique", "gear", "stories");
    private final ArticleRepository articles;
    private final ArticleCoverRepository covers;
    private final UserRepository users;

    public ArticleService(ArticleRepository articles, ArticleCoverRepository covers, UserRepository users) {
        this.articles = articles; this.covers = covers; this.users = users;
    }

    public Page<Article> search(boolean admin, String state, String category, String query, int page, int size) {
        return search(admin, state, category, query, page, size, "");
    }

    public Page<Article> search(boolean admin, String state, String category, String query, int page, int size, String authorEmail) {
        Boolean published = admin ? switch (state) {
            case "published" -> true;
            case "draft" -> false;
            default -> null;
        } : Boolean.TRUE;
        String term = query.strip().toLowerCase(Locale.ROOT);
        if (term.length() > 160) term = term.substring(0, 160);
        term = term.isEmpty() ? "" : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return articles.search(published, category, term, authorEmail, PageRequest.of(Math.max(0, Math.min(page, 100000)), size,
                Sort.by(Sort.Direction.DESC, admin ? "updatedAt" : "publishedAt").and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    public Article find(Long id, boolean admin) {
        return (admin ? articles.findById(id) : articles.findByIdAndPublishedTrue(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public ArticleCover cover(Long id, boolean admin) {
        find(id, admin);
        return covers.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Article save(Long id, Map<String, String> form, MultipartFile upload, String email) {
        return save(id, form, upload, email, true);
    }

    @Transactional
    public Article save(Long id, Map<String, String> form, MultipartFile upload, String email, boolean publisher) {
        Article article = id == null ? new Article() : checked(id, form.get("version"));
        if (!publisher && (!"draft".equals(form.get("action")) || article.isPublished()
                || (id != null && !article.getAuthor().getEmail().equalsIgnoreCase(email)))) {
            throw new org.springframework.security.access.AccessDeniedException("Article editing is not permitted for this account.");
        }
        String title = value(form, "title"), summary = value(form, "summary"), body = value(form, "body");
        String category = value(form, "category"), alt = value(form, "coverAlt"), action = value(form, "action");
        if (title.isBlank() || title.length() > 160) throw new InvalidArticle("article.error.title");
        if (summary.length() > 500 || body.length() > 50000 || alt.length() > 200) throw new InvalidArticle("article.error.length");
        if (!CATEGORIES.contains(category)) throw new InvalidArticle("article.error.category");
        if (!List.of("draft", "publish").contains(action)) throw new InvalidArticle("article.error.action");
        boolean publish = action.equals("publish");
        if (publish && (summary.isBlank() || body.isBlank())) throw new InvalidArticle("article.error.publish");
        boolean remove = "true".equals(form.get("removeCover"));
        boolean replace = upload != null && !upload.isEmpty();
        if (remove && replace) throw new InvalidArticle("article.error.coverChoice");
        if ((replace || (article.isCoverPresent() && !remove)) && alt.isBlank()) throw new InvalidArticle("article.error.alt");
        ImageData image = replace ? readImage(upload) : null;

        if (id == null) article.setAuthor(users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN)));
        article.setTitle(title); article.setSummary(summary); article.setBody(body); article.setCategory(category);
        article.setCoverAlt(alt); article.setUpdatedAt(Instant.now());
        applyPublication(article, publish);
        article = articles.saveAndFlush(article);
        if (remove && article.isCoverPresent()) { covers.deleteById(article.getId()); article.setCoverPresent(false); }
        if (image != null) {
            Article current = article;
            ArticleCover cover = covers.findById(article.getId())
                    .orElseGet(() -> new ArticleCover(current, image.type(), image.data()));
            cover.replace(image.type(), image.data());
            covers.save(cover);
            article.setCoverPresent(true);
        }
        articles.flush();
        return article;
    }

    @Transactional
    public void publication(Long id, String version, boolean publish) {
        Article article = checked(id, version);
        if (publish && (article.getSummary().isBlank() || article.getBody().isBlank())) throw new InvalidArticle("article.error.publish");
        applyPublication(article, publish);
        article.setUpdatedAt(Instant.now());
        articles.flush();
    }

    @Transactional
    public void delete(Long id, String version) {
        Article article = checked(id, version);
        if (article.isCoverPresent()) { covers.deleteById(id); covers.flush(); }
        articles.delete(article);
        articles.flush();
    }

    private Article checked(Long id, String version) {
        Article article = find(id, true);
        if (!Objects.equals(Long.toString(article.getVersion()), version)) throw new InvalidArticle("article.error.conflict");
        return article;
    }

    private void applyPublication(Article article, boolean publish) {
        if (publish && !article.isPublished()) article.setPublishedAt(Instant.now());
        article.setPublished(publish);
    }

    private String value(Map<String, String> form, String key) { return form.getOrDefault(key, "").strip(); }

    private ImageData readImage(MultipartFile upload) {
        if (upload.getSize() > 5 * 1024 * 1024) throw new InvalidArticle("article.error.cover");
        try {
            byte[] data = upload.getBytes();
            try (var stream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException();
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String type = reader.getFormatName().toLowerCase(Locale.ROOT);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (!List.of("jpeg", "png").contains(type) || width > 10000 || height > 10000
                            || (long) width * height > 25000000 || reader.read(0) == null) throw new IOException();
                    return new ImageData(type.equals("jpeg") ? "image/jpeg" : "image/png", data);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException exception) { throw new InvalidArticle("article.error.cover"); }
    }

    private record ImageData(String type, byte[] data) { }
    public static class InvalidArticle extends RuntimeException {
        public InvalidArticle(String key) { super(key); }
    }
}
