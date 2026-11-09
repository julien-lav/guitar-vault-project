package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "luthier_profiles")
public class LuthierProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true) private User user;
    @Version private long version;
    @Column(nullable = false, length = 150) private String name;
    @Column(nullable = false, length = 200) private String location;
    @Column(nullable = false, length = 500) private String specialties;
    @Column(nullable = false, length = 3000) private String description;
    @Column(nullable = false, length = 300) private String contact;
    @Column(nullable = false) private boolean published;
    private Instant verifiedAt;
    protected LuthierProfile() { }
    public LuthierProfile(User user) { this.user = user; }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public long getVersion() { return version; }
    public String getName() { return name; }
    public String getLocation() { return location; }
    public String getSpecialties() { return specialties; }
    public String getDescription() { return description; }
    public String getContact() { return contact; }
    public boolean isPublished() { return published; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public boolean isVerified() { return verifiedAt != null; }
    public void update(String name, String location, String specialties, String description, String contact, boolean published) {
        // Identity changes require a fresh administrative review.
        if (!java.util.Objects.equals(this.name, name) || !java.util.Objects.equals(this.location, location)
                || !java.util.Objects.equals(this.contact, contact)) verifiedAt = null;
        this.name = name; this.location = location; this.specialties = specialties;
        this.description = description; this.contact = contact; this.published = published;
    }
    void verify(boolean verified) { verifiedAt = verified ? Instant.now() : null; }
}
