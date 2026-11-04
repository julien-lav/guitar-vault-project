package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "profile_photos")
public class ProfilePhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private User owner;
    @Column(nullable = false)
    private String contentType;
    @Lob @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;

    protected ProfilePhoto() { }
    public ProfilePhoto(User owner, String contentType, byte[] data) {
        this.owner = owner;
        replace(contentType, data);
    }
    public void replace(String contentType, byte[] data) { this.contentType = contentType; this.data = data; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
