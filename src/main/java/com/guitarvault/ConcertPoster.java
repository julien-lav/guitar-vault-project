package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "concert_posters")
public class ConcertPoster {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id", nullable = false, unique = true) private Concert concert;
    @Column(nullable = false) private String contentType;
    @Lob @Basic(fetch = FetchType.LAZY) @Column(nullable = false) private byte[] data;
    protected ConcertPoster() { }
    ConcertPoster(Concert concert) { this.concert = concert; }
    void replace(String contentType, byte[] data) { this.contentType = contentType; this.data = data; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
