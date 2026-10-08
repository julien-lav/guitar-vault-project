package com.guitarvault;

public class Instrument {

    private final Long id;
    private final String name;
    private final String brand;
    private final Integer year;
    private final String owner;
    private final String status;

    public Instrument(Long id, String name, String brand, Integer year, String owner, String status) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.year = year;
        this.owner = owner;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public Integer getYear() {
        return year;
    }

    public String getOwner() {
        return owner;
    }

    public String getStatus() {
        return status;
    }
}
