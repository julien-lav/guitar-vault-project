package com.guitarvault;

import jakarta.persistence.*;
import java.time.LocalTime;

@Embeddable
public class WorkshopDayHours {
    private LocalTime opens;
    private LocalTime closes;
    private LocalTime breakStart;
    private LocalTime breakEnd;
    protected WorkshopDayHours() { }
    WorkshopDayHours(LocalTime opens, LocalTime closes, LocalTime breakStart, LocalTime breakEnd) {
        this.opens = opens; this.closes = closes; this.breakStart = breakStart; this.breakEnd = breakEnd;
    }
    public LocalTime getOpens() { return opens; }
    public LocalTime getCloses() { return closes; }
    public LocalTime getBreakStart() { return breakStart; }
    public LocalTime getBreakEnd() { return breakEnd; }
}
