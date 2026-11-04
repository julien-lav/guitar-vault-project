package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "bands")
public class Band {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 100)
    private String genre;
    @Column(length = 100)
    private String city;
    @Column(length = 5000)
    private String description;
    @Column(length = 2000)
    private String spotifyUrl;
    @Column(length = 2000)
    private String deezerUrl;
    @Column(length = 2000)
    private String websiteUrl;
    @ManyToMany
    @JoinTable(name = "band_members", joinColumns = @JoinColumn(name = "band_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"band_id", "user_id"}))
    @OrderBy("username ASC")
    private Set<User> members = new LinkedHashSet<>();
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Band() { }
    public Band(User creator) { this.creator = creator; }
    public Long getId() { return id; }
    public User getCreator() { return creator; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getGenre() { return genre; }
    public void setGenre(String value) { genre = value; }
    public String getCity() { return city; }
    public void setCity(String value) { city = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getSpotifyUrl() { return spotifyUrl; }
    public void setSpotifyUrl(String value) { spotifyUrl = value; }
    public String getDeezerUrl() { return deezerUrl; }
    public void setDeezerUrl(String value) { deezerUrl = value; }
    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String value) { websiteUrl = value; }
    public Set<User> getMembers() { return members; }
    public Instant getCreatedAt() { return createdAt; }
}
