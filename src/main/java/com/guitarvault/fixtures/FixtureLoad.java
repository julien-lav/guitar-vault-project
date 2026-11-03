package com.guitarvault.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "fixture_loads")
public class FixtureLoad {
    @Id private String name;
    private Instant loadedAt;

    protected FixtureLoad() { }
    public FixtureLoad(String name) { this.name = name; this.loadedAt = Instant.now(); }
    public String getName() { return name; }
    public Instant getLoadedAt() { return loadedAt; }
}
