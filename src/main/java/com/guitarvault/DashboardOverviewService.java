package com.guitarvault;

import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, participant-scoped previews; no photo bytes or unrelated conversations are loaded. */
@Service
public class DashboardOverviewService {
    private final InstrumentRepository instruments;
    private final LuthierAppointmentRepository appointments;
    private final ShopConversationRepository shopConversations;
    private final InstrumentTransferRepository transfers;
    private final WorkshopJobRepository jobs;
    public DashboardOverviewService(InstrumentRepository instruments, LuthierAppointmentRepository appointments,
            ShopConversationRepository shopConversations, InstrumentTransferRepository transfers, WorkshopJobRepository jobs) {
        this.instruments = instruments; this.appointments = appointments; this.shopConversations = shopConversations; this.transfers = transfers; this.jobs = jobs;
    }
    public record Task(String label, String title, String url) { }
    public record Visit(String title, String venue, String url, Instant at, LocalDateTime local, String zone) { }
    public record Overview(List<Task> tasks, List<Visit> visits, Map<Long,Long> photos) { }
    @Transactional(readOnly = true)
    public Overview forUser(User actor, List<Instrument> recent) {
        Long id = actor.getId(); Instant now = Instant.now(); var limit = PageRequest.of(0, 5);
        var tasks = new ArrayList<Task>();
        for (var job : jobs.awaitingOwner(id, limit)) tasks.add(new Task(job.getStage() == WorkshopJob.Stage.CHANGE_PENDING ? "ui.task.change" : "ui.task.quote", job.getIntervention().getInstrument().getName(), "/interventions/" + job.getIntervention().getId()));
        for (var transfer : transfers.findByRecipientIdAndStatusOrderByIdDesc(id, InstrumentTransfer.Status.PENDING, limit)) tasks.add(new Task("ui.task.transfer", transfer.getInstrumentName(), "/transfers/" + transfer.getId()));
        for (var a : appointments.awaitingActor(id, now, limit)) tasks.add(new Task("ui.task.time", a.getTitle(), "/appointments/" + a.getId()));
        for (var c : shopConversations.awaitingActor(id, now, limit)) tasks.add(new Task("ui.task.time", c.getTitle(), "/shop-conversations/" + c.getId()));
        var visits = new ArrayList<Visit>();
        for (var a : appointments.upcoming(id, now, limit)) visits.add(new Visit(a.getTitle(), a.getWorkshop().getName(), "/appointments/" + a.getId(), a.getProposedAt(), a.getProposedLocal(), a.getTimeZone()));
        for (var c : shopConversations.upcoming(id, now, limit)) visits.add(new Visit(c.getTitle(), c.getShop().getName(), "/shop-conversations/" + c.getId(), c.getProposedAt(), c.getProposedLocal(), c.getTimeZone()));
        visits.sort(Comparator.comparing(Visit::at).thenComparing(Visit::url));
        var photos = new LinkedHashMap<Long,Long>();
        if (!recent.isEmpty()) for (var photo : instruments.previewPhotos(id, recent.stream().map(Instrument::getId).toList())) photos.put(photo.getInstrumentId(), photo.getPhotoId());
        return new Overview(tasks.stream().limit(5).toList(), visits.stream().limit(4).toList(), Map.copyOf(photos));
    }
}
