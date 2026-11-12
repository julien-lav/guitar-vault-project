package com.guitarvault;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "workshop_jobs")
public class WorkshopJob {
    public enum Stage { RECEIVED, QUOTED, APPROVED, QUOTE_DECLINED, IN_PROGRESS, CHANGE_PENDING, CHANGE_DECLINED, READY, COLLECTED, CANCELLED }
    @Converter
    public static class StageConverter implements AttributeConverter<Stage, String> {
        public String convertToDatabaseColumn(Stage stage) { return stage == null ? null : stage.name(); }
        public Stage convertToEntityAttribute(String value) { return value == null ? null : Stage.valueOf(value); }
    }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false, unique = true) private LuthierIntervention intervention;
    @Version private long version;
    @Convert(converter = StageConverter.class) @Column(nullable = false, length = 30) private Stage stage = Stage.RECEIVED;
    @Column(precision = 12, scale = 2) private BigDecimal amount;
    @Column(length = 3) private String currency;
    @Column(length = 3000) private String scope;
    private java.time.LocalDate estimatedCompletionOn;
    @Column(precision = 12, scale = 2) private BigDecimal proposedAmount;
    @Column(length = 3000) private String proposedScope;
    @Column(length = 3000) private String changeReason;
    private java.time.LocalDate proposedCompletionOn;
    public java.time.LocalDate getEstimatedCompletionOn() { return estimatedCompletionOn; }
    public BigDecimal getProposedAmount() { return proposedAmount; }
    public BigDecimal getChangeInTotal() { return proposedAmount == null ? null : proposedAmount.subtract(amount); }
    public String getProposedScope() { return proposedScope; }
    public String getChangeReason() { return changeReason; }
    public java.time.LocalDate getProposedCompletionOn() { return proposedCompletionOn; }
    void proposeChange(BigDecimal total, String scope, String reason, java.time.LocalDate completion) {
        proposedAmount = total; proposedScope = scope; changeReason = reason; proposedCompletionOn = completion;
        stage = Stage.CHANGE_PENDING;
    }
    void approveChange() {
        amount = proposedAmount; scope = proposedScope; estimatedCompletionOn = proposedCompletionOn;
        stage = Stage.IN_PROGRESS;
    }
    protected WorkshopJob() { }
    WorkshopJob(LuthierIntervention intervention) { this.intervention = intervention; }
    public Long getId() { return id; }
    public LuthierIntervention getIntervention() { return intervention; }
    public long getVersion() { return version; }
    public Stage getStage() { return stage; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getScope() { return scope; }
    public boolean isClosed() { return stage == Stage.COLLECTED || stage == Stage.CANCELLED; }
    void move(Stage stage) { this.stage = stage; }
    void quote(BigDecimal amount, String currency, String scope) {
        this.amount = amount; this.currency = currency; this.scope = scope; stage = Stage.QUOTED;
    }
    // Every activity invalidates older forms, including a quote with identical terms.
    @Column(nullable = false) private long activity;
    void touch() { activity++; }
}
