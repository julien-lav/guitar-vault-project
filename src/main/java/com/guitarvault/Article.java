package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "articles", indexes = @Index(name = "article_publication_idx", columnList = "published,publishedAt"))
public class Article {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version private long version;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, length = 500) private String summary = "";
    @Column(nullable = false, length = 50000) private String body = "";
    @Column(nullable = false, length = 30) private String category = "guides";
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "author_id", nullable = false) private User author;
    @Column(nullable = false) private boolean published;
    @Column(nullable = false) private boolean coverPresent;
    @Column(nullable = false, length = 200) private String coverAlt = "";
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    private Instant publishedAt;

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public String getBody() { return body; }
    public void setBody(String value) { body = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public User getAuthor() { return author; }
    public void setAuthor(User value) { author = value; }
    public String getAuthorName() { return author.getFullName(); }
    public boolean isPublished() { return published; }
    public void setPublished(boolean value) { published = value; }
    public boolean isCoverPresent() { return coverPresent; }
    public void setCoverPresent(boolean value) { coverPresent = value; }
    public String getCoverAlt() { return coverAlt; }
    public void setCoverAlt(String value) { coverAlt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant value) { publishedAt = value; }
    public String getDisplayDate() {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC)
                .format(publishedAt == null ? updatedAt : publishedAt);
    }
    public int getReadingMinutes() {
        return Math.max(1, (body.isBlank() ? 0 : body.strip().split("\\s+").length + 199) / 200);
    }
}
