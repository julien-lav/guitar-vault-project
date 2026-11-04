package com.guitarvault;

import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;

@Entity
@Table(name = "studios", indexes = @Index(name = "idx_studio_creator", columnList = "creator_id"))
public class Studio {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 100)
    private String city;
    @Column(length = 100)
    private String country;
    @Column(length = 5000)
    private String description;
    @Column(length = 5000)
    private String services;
    @Column(length = 5000)
    private String equipment;
    @Column(length = 2000)
    private String websiteUrl;
    @Column(length = 254)
    private String contactEmail;
    @Column(precision = 8, scale = 2)
    private BigDecimal hourlyRate;
    private Integer capacity;
    @Column(precision = 8, scale = 2)
    private BigDecimal mixingRate;
    @Column(precision = 8, scale = 2)
    private BigDecimal masteringRate;
    @Column(precision = 8, scale = 2)
    private BigDecimal dayRate;
    @Column(length = 1000)
    private String pricingNotes;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Studio() { }
    public Studio(User creator) { this.creator = creator; }
    public Long getId() { return id; }
    public User getCreator() { return creator; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getCity() { return city; }
    public void setCity(String value) { city = value; }
    public String getCountry() { return country; }
    public void setCountry(String value) { country = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getServices() { return services; }
    public void setServices(String value) { services = value; }
    public String getEquipment() { return equipment; }
    public void setEquipment(String value) { equipment = value; }
    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String value) { websiteUrl = value; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String value) { contactEmail = value; }
    public Instant getCreatedAt() { return createdAt; }
    public BigDecimal getHourlyRate() { return hourlyRate; }
    public void setHourlyRate(BigDecimal value) { hourlyRate = value; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer value) { capacity = value; }
    public BigDecimal getMixingRate() { return mixingRate; }
    public void setMixingRate(BigDecimal value) { mixingRate = value; }
    public BigDecimal getMasteringRate() { return masteringRate; }
    public void setMasteringRate(BigDecimal value) { masteringRate = value; }
    public BigDecimal getDayRate() { return dayRate; }
    public void setDayRate(BigDecimal value) { dayRate = value; }
    public String getPricingNotes() { return pricingNotes; }
    public void setPricingNotes(String value) { pricingNotes = value; }
}
