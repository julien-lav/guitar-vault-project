package com.guitarvault;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;

@Entity
@Table(name = "users")
public class User {

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean moderationHidden;
    public boolean isModerationHidden() { return moderationHidden; }
    void setModerationHidden(boolean value) { moderationHidden = value; }
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean suspended;
    public boolean isSuspended() { return suspended; }
    void setSuspended(boolean value) { suspended = value; }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name")
    private String fullName;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String instrument;
    private String level;
    private String city;
    private String country;

    @Column(length = 1000)
    private String bio;

    @Column(length = 100)
    private String displayName;
    @Column(length = 2000)
    private String spotifyUrl;
    @Column(length = 2000)
    private String deezerUrl;
    @Column(length = 2000)
    private String websiteUrl;
    @ElementCollection
    @CollectionTable(name = "user_activities", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "activity", nullable = false, length = 30)
    private Set<ProfileActivity> activities = EnumSet.noneOf(ProfileActivity.class);

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getSpotifyUrl() { return spotifyUrl; }
    public void setSpotifyUrl(String value) { spotifyUrl = value; }
    public String getDeezerUrl() { return deezerUrl; }
    public void setDeezerUrl(String value) { deezerUrl = value; }
    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String value) { websiteUrl = value; }
    public Set<ProfileActivity> getActivities() { return Set.copyOf(activities); }
    public void setActivities(Set<ProfileActivity> values) {
        activities = new java.util.HashSet<>(values);
    }

    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<Role> roles = EnumSet.of(Role.ROLE_USER);

    public Set<Role> getRoles() {
        // Accounts created before roles were introduced keep ordinary member access.
        EnumSet<Role> result = EnumSet.of(Role.ROLE_USER);
        result.addAll(roles);
        return Set.copyOf(result);
    }

    public void setRoles(Set<Role> values) {
        java.util.Objects.requireNonNull(values);
        roles.clear();
        roles.add(Role.ROLE_USER);
        roles.addAll(values);
    }

    @Column(unique = true, length = 64)
    private String verificationTokenHash;

    private Instant verificationExpiresAt;
    private Instant verificationRequestedAt;

    public Instant getVerificationRequestedAt() { return verificationRequestedAt; }
    public void setVerificationRequestedAt(Instant value) { verificationRequestedAt = value; }

    @Column(unique = true, length = 64)
    private String passwordResetHash;
    private Instant passwordResetExpiresAt;
    private Instant passwordResetRequestedAt;

    public String getPasswordResetHash() { return passwordResetHash; }
    public void setPasswordResetHash(String value) { passwordResetHash = value; }
    public Instant getPasswordResetExpiresAt() { return passwordResetExpiresAt; }
    public void setPasswordResetExpiresAt(Instant value) { passwordResetExpiresAt = value; }
    public Instant getPasswordResetRequestedAt() { return passwordResetRequestedAt; }
    public void setPasswordResetRequestedAt(Instant value) { passwordResetRequestedAt = value; }

    private Instant createdAt = Instant.now();

    public User() {
    }

    // Getters and setters


    public String getVerificationTokenHash() {
        return verificationTokenHash;
    }

    public void setVerificationTokenHash(String value) {
        verificationTokenHash = value;
    }

    public Instant getVerificationExpiresAt() {
        return verificationExpiresAt;
    }

    public void setVerificationExpiresAt(Instant value) {
        verificationExpiresAt = value;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getInstrument() {
        return instrument;
    }

    public void setInstrument(String instrument) {
        this.instrument = instrument;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public boolean isEnabled() {
        return enabled && !suspended;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
