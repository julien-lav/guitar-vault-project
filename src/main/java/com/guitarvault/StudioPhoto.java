package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "studio_photos", indexes = @Index(name = "idx_studio_photo", columnList = "studio_id"))
public class StudioPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "studio_id", nullable = false)
    private Studio studio;
    @Column(nullable = false)
    private String contentType;
    @Lob @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;

    protected StudioPhoto() { }
    public StudioPhoto(Studio studio, String contentType, byte[] data) {
        this.studio = studio;
        this.contentType = contentType;
        this.data = data;
    }
    public Long getId() { return id; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
