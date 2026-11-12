package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "vintage_shops")
public class VintageShop {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, unique = true) private User user;
    @Version private long version;
    @Column(nullable = false, length = 150) private String name;
    @Column(nullable = false, length = 200) private String location;
    @Column(nullable = false, length = 3000) private String description;
    @Column(nullable = false, length = 300) private String contact;
    @Column(nullable = false, length = 500) private String openingHours;
    @Column(nullable = false) private boolean published;
    protected VintageShop() { }
    VintageShop(User user) { this.user = user; }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public long getVersion() { return version; }
    public String getName() { return name; }
    public String getLocation() { return location; }
    public String getDescription() { return description; }
    public String getContact() { return contact; }
    public String getOpeningHours() { return openingHours; }
    public boolean isPublished() { return published; }
    void update(String name, String location, String description, String contact, String openingHours, boolean published) {
        this.name = name; this.location = location; this.description = description; this.contact = contact;
        this.openingHours = openingHours; this.published = published;
    }
}
