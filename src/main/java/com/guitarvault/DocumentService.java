package com.guitarvault;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentService {
    private final InstrumentDocumentRepository documents;
    private final DocumentLibraryRepository library;
    private final DocumentContentRepository contents;
    private final DocumentGrantRepository grants;
    private final InstrumentRepository instruments;
    private final LuthierAssessmentRepository assessments;
    public DocumentService(InstrumentDocumentRepository documents, DocumentContentRepository contents,
            DocumentGrantRepository grants, InstrumentRepository instruments, LuthierAssessmentRepository assessments, DocumentLibraryRepository library) {
        this.documents = documents; this.library = library; this.contents = contents; this.grants = grants; this.instruments = instruments; this.assessments = assessments;
    }
    @Transactional
    public void upload(User actor, Long instrumentId, String title, String kind, MultipartFile file) {
        Instrument instrument = instruments.findForUpdate(instrumentId).orElseThrow(DocumentService::notFound);
        if (!instrument.getOwner().getId().equals(actor.getId())) throw notFound();
        InstrumentDocument.Kind type;
        try { type = InstrumentDocument.Kind.valueOf(kind); }
        catch (RuntimeException e) { throw invalid(); }
        if (type == InstrumentDocument.Kind.EVIDENCE) throw invalid();
        save(actor, instrument, null, title, type, InstrumentDocument.Visibility.PRIVATE, file);
    }
    public LuthierAssessment authoredReport(User actor, Long id) {
        return assessments.findById(id).filter(r -> r.getAuthor().getId().equals(actor.getId())).orElseThrow(DocumentService::notFound);
    }
    @Transactional
    public void attach(User actor, Long reportId, String title, String visibility, MultipartFile file) {
        LuthierAssessment report = authoredReport(actor, reportId);
        Instrument instrument = instruments.findForUpdate(report.getIntervention().getInstrument().getId()).orElseThrow(DocumentService::notFound);
        InstrumentDocument.Visibility scope;
        try { scope = InstrumentDocument.Visibility.valueOf(visibility); }
        catch (RuntimeException e) { throw invalid(); }
        save(actor, instrument, report, title, InstrumentDocument.Kind.EVIDENCE, scope, file);
    }
    private void save(User actor, Instrument instrument, LuthierAssessment report, String title,
            InstrumentDocument.Kind kind, InstrumentDocument.Visibility scope, MultipartFile file) {
        if (title == null || title.isBlank() || title.strip().length() > 150) throw invalid();
        if (documents.countByInstrumentIdAndUploaderId(instrument.getId(), actor.getId()) >= 20)
            throw new IllegalArgumentException("document.limit");
        var upload = DocumentUploadValidator.validate(file);
        InstrumentDocument document = documents.saveAndFlush(new InstrumentDocument(instrument, actor, report, title.strip(),
                upload.filename(), upload.contentType(), upload.data().length, kind, scope));
        contents.save(new DocumentContent(document, upload.data()));
    }
    public InstrumentDocument visible(User actor, Long id) {
        return documents.findById(id).filter(d -> canRead(actor, d)).orElseThrow(DocumentService::notFound);
    }
    public boolean canRead(User actor, InstrumentDocument d) {
        if (d.getUploader().getId().equals(actor.getId())) return true;
        if (d.getAssessment() == null) return grants.existsByDocumentIdAndRecipientId(d.getId(), actor.getId());
        if (d.getVisibility() == InstrumentDocument.Visibility.PRIVATE) return false;
        if (d.getAssessment().getIntervention().getRequester().getId().equals(actor.getId())) return true;
        return d.getVisibility() == InstrumentDocument.Visibility.REPORT && d.getInstrument().getOwner().getId().equals(actor.getId());
    }
    public List<InstrumentDocument> transferable(User actor, Long instrumentId) {
        Set<Long> archived = new HashSet<>();
        library.findByReaderId(actor.getId()).stream().filter(DocumentLibraryEntry::isArchived).forEach(e -> archived.add(e.getDocumentId()));
        return documents.transferableDocuments(instrumentId, actor.getId()).stream().filter(d -> !archived.contains(d.getId())).toList();
    }
    public List<InstrumentDocument> selected(User actor, Instrument instrument, List<Long> ids) {
        Set<Long> unique = new HashSet<>(ids == null ? List.of() : ids);
        if (unique.size() > 50 || unique.contains(null)) throw invalid();
        List<InstrumentDocument> result = documents.findAllById(unique);
        if (result.size() != unique.size() || result.stream().anyMatch(d -> !d.getInstrument().getId().equals(instrument.getId())
                || d.getAssessment() != null || !canRead(actor, d) || library.existsByDocument_IdAndReader_IdAndArchivedTrue(d.getId(), actor.getId()))) throw notFound();
        return result;
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void grantSelected(InstrumentTransfer transfer) {
        for (InstrumentDocument d : transfer.getDocuments()) {
            if (!grants.existsByDocumentIdAndRecipientId(d.getId(), transfer.getRecipient().getId()))
                grants.save(new DocumentGrant(d, transfer.getRecipient(), transfer));
        }
    }
    public Map<Long, List<InstrumentDocument>> reportAttachments(User actor, List<LuthierAssessment> reports) {
        Map<Long, List<InstrumentDocument>> result = new HashMap<>();
        for (var report : reports) result.put(report.getId(), new ArrayList<>());
        if (!reports.isEmpty()) {
            for (var d : documents.findByAssessmentIdInOrderByIdAsc(reports.stream().map(LuthierAssessment::getId).toList()))
                if (canRead(actor, d)) result.get(d.getAssessment().getId()).add(d);
        }
        return result;
    }
    public List<InstrumentDocument> personal(User actor) {
        List<InstrumentDocument> result = new ArrayList<>(documents.personalDocuments(actor.getId()));
        result.addAll(documents.findByUploaderIdAndAssessmentIsNotNullOrderByIdDesc(actor.getId()));
        return result;
    }
    public byte[] bytes(User actor, Long id) {
        visible(actor, id);
        return contents.findById(id).orElseThrow(DocumentService::notFound).getData();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("document.invalid"); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
