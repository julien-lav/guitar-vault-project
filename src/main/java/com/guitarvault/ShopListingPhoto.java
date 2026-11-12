package com.guitarvault;

import jakarta.persistence.*;

@Entity
@Table(name = "shop_listing_photos")
public class ShopListingPhoto {
    @Id private Long id;
    @MapsId @OneToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "listing_id") private ShopListing listing;
    @Column(nullable = false) private String contentType;
    @Lob @Column(nullable = false) private byte[] data;
    protected ShopListingPhoto() { }
    ShopListingPhoto(ShopListing listing, String contentType, byte[] data) { this.listing = listing; this.contentType = contentType; this.data = data; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
    void replace(String type, byte[] bytes) { contentType = type; data = bytes; }
}
