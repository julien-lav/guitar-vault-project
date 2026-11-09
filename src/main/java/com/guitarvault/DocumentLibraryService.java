package com.guitarvault;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentLibraryService {
    public enum Access { PRIVATE, TRANSFER, REQUEST, REPORT }
    public enum State { ACTIVE, ARCHIVED, ALL }
    public record Filter(String query, Long instrumentId, InstrumentDocument.Kind category, Access access, State state) {
        public Filter {
            query = query == null ? "" : query.strip();
            if (query.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            if (state == null) state = State.ACTIVE;
        }
    }
    public record InstrumentChoice(Long id, String name) { }
    public record Row(Long id, String title, String originalTitle, InstrumentDocument.Kind kind,
            InstrumentDocument.Kind originalKind, String filename, long byteSize, Long instrumentId,
            String instrumentName, Access access, boolean archived, long version, boolean canDelete, boolean customLabels) { }
    public record Library(Page<Row> page, List<InstrumentChoice> instruments) { }
    private final InstrumentDocumentRepository documents;
    private final DocumentContentRepository contents;
    private final DocumentGrantRepository grants;
    private final DocumentLibraryRepository entries;
    private final InstrumentTransferRepository transfers;
    private final InstrumentRepository instruments;
    private final DocumentService permissions;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    public DocumentLibraryService(InstrumentDocumentRepository documents, DocumentContentRepository contents,
            DocumentGrantRepository grants, DocumentLibraryRepository entries, InstrumentTransferRepository transfers,
            InstrumentRepository instruments, DocumentService permissions) {
        this.documents = documents; this.contents = contents; this.grants = grants; this.entries = entries;
        this.transfers = transfers; this.instruments = instruments; this.permissions = permissions;
    }
    private List<Row> rows(User actor) {
        List<InstrumentDocument> readable = documents.libraryDocuments(actor.getId(),
                List.of(InstrumentDocument.Visibility.REQUEST, InstrumentDocument.Visibility.REPORT), InstrumentDocument.Visibility.REPORT);
        Map<Long, DocumentLibraryEntry> labels = new HashMap<>();
        entries.findByReaderId(actor.getId()).forEach(e -> labels.put(e.getDocumentId(), e));
        List<Long> ids = readable.stream().map(InstrumentDocument::getId).toList();
        Set<Long> referenced = ids.isEmpty() ? Set.of() : new HashSet<>(transfers.referencedDocumentIds(ids));
        Set<Long> granted = ids.isEmpty() ? Set.of() : new HashSet<>(grants.grantedDocumentIds(ids));
        return readable.stream().map(d -> row(actor, d, labels.get(d.getId()), referenced.contains(d.getId()) || granted.contains(d.getId()))).toList();
    }
    private Row row(User actor, InstrumentDocument d, DocumentLibraryEntry entry, boolean transferred) {
        boolean retained = d.getAssessment() != null || transferred;
        Access access = d.getAssessment() == null ? (transferred ? Access.TRANSFER : Access.PRIVATE)
                : switch (d.getVisibility()) { case PRIVATE -> Access.PRIVATE; case REQUEST -> Access.REQUEST; case REPORT -> Access.REPORT; };
        String title = entry != null && entry.getTitle() != null ? entry.getTitle() : d.getTitle();
        var kind = entry != null && entry.getKind() != null ? entry.getKind() : d.getKind();
        return new Row(d.getId(), title, d.getTitle(), kind, d.getKind(), d.getFilename(), d.getByteSize(),
                d.getInstrument().getId(), d.getInstrument().getName(), access, entry != null && entry.isArchived(),
                entry == null ? -1 : entry.getVersion(), d.getUploader().getId().equals(actor.getId()) && !retained,
                !title.equals(d.getTitle()) || kind != d.getKind());
    }
    @Transactional(readOnly = true)
    public Library search(User actor, Filter filter, int page) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<Row> all = rows(actor);
        List<InstrumentChoice> choices = all.stream().map(r -> new InstrumentChoice(r.instrumentId(), r.instrumentName()))
                .distinct().sorted(Comparator.comparing(InstrumentChoice::name).thenComparing(InstrumentChoice::id)).toList();
        List<Row> filtered = all.stream().filter(r -> matches(r, filter)).toList();
        int from = (int)Math.min((long)page * 20, filtered.size());
        return new Library(new PageImpl<>(filtered.subList(from, Math.min(from + 20, filtered.size())), PageRequest.of(page, 20), filtered.size()), choices);
    }
    @Transactional(readOnly = true)
    public List<Row> forInstrument(User actor, Long instrumentId) {
        return rows(actor).stream().filter(r -> r.instrumentId().equals(instrumentId) && !r.archived()).toList();
    }
    private boolean matches(Row r, Filter f) {
        return (f.instrumentId() == null || f.instrumentId().equals(r.instrumentId()))
                && (f.category() == null || f.category() == r.kind()) && (f.access() == null || f.access() == r.access())
                && (f.state() == State.ALL || r.archived() == (f.state() == State.ARCHIVED))
                && (f.query().isEmpty() || (r.title() + " " + r.originalTitle() + " " + r.filename()).toLowerCase(Locale.ROOT).contains(f.query().toLowerCase(Locale.ROOT)));
    }
    @Transactional(readOnly = true)
    public Row detail(User actor, Long id) {
        InstrumentDocument d = permissions.visible(actor, id);
        return row(actor, d, entries.findByDocument_IdAndReader_Id(id, actor.getId()).orElse(null),
                transfers.existsByDocumentsId(id) || grants.existsByDocumentId(id));
    }
    private InstrumentDocument locked(User actor, Long id) {
        InstrumentDocument d = permissions.visible(actor, id);
        Instrument instrument = instruments.findForUpdate(d.getInstrument().getId()).orElseThrow(DocumentLibraryService::notFound);
        entityManager.refresh(instrument);
        if (!documents.existsById(id) || !permissions.canRead(actor, d)) throw notFound();
        return d;
    }
    private DocumentLibraryEntry entry(User actor, InstrumentDocument d, long version) {
        var current = entries.findByDocument_IdAndReader_Id(d.getId(), actor.getId()).orElse(null);
        if (current != null) entityManager.refresh(current);
        if ((current == null ? -1 : current.getVersion()) != version) throw conflict("document.library.stale");
        return current == null ? new DocumentLibraryEntry(d, actor) : current;
    }
    @Transactional
    public void edit(User actor, Long id, long version, String title, String category) {
        InstrumentDocument d = locked(actor, id);
        if (title == null || title.isBlank() || title.strip().length() > 150) throw new IllegalArgumentException("document.invalid");
        InstrumentDocument.Kind kind;
        try { kind = InstrumentDocument.Kind.valueOf(category); }
        catch (RuntimeException e) { throw new IllegalArgumentException("document.invalid"); }
        var personal = entry(actor, d, version);
        personal.label(title.strip(), kind);
        entries.saveAndFlush(personal);
    }
    @Transactional
    public void archive(User actor, Long id, long version, boolean archived) {
        var personal = entry(actor, locked(actor, id), version);
        personal.archive(archived);
        entries.saveAndFlush(personal);
    }
    @Transactional(readOnly = true)
    public Row deletable(User actor, Long id) {
        InstrumentDocument d = permissions.visible(actor, id);
        if (!d.getUploader().getId().equals(actor.getId())) throw notFound();
        Row row = detail(actor, id);
        if (!row.canDelete()) throw conflict("document.library.protected");
        return row;
    }
    @Transactional
    public void delete(User actor, Long id, long version) {
        InstrumentDocument d = locked(actor, id);
        if (!d.getUploader().getId().equals(actor.getId())) throw notFound();
        entry(actor, d, version);
        if (d.getAssessment() != null || transfers.existsByDocumentsId(id) || grants.existsByDocumentId(id))
            throw conflict("document.library.protected");
        entries.deleteAll(entries.findByDocument_Id(id));
        entries.flush();
        contents.deleteById(id);
        contents.flush();
        documents.delete(d);
        documents.flush();
    }
    private static ResponseStatusException conflict(String key) { return new ResponseStatusException(HttpStatus.CONFLICT, key); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
