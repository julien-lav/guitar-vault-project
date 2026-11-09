package com.guitarvault;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class DocumentLibraryTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired InstrumentDocumentRepository documents;
    @Autowired DocumentContentRepository contents;
    @Autowired DocumentService uploads;
    @Autowired DocumentLibraryService library;
    @Autowired DocumentLibraryRepository entries;
    @Autowired InstrumentTransferService transfers;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierInterventionRepository interventions;
    @Autowired LuthierAssessmentRepository assessments;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User owner, buyer, stranger, admin;
    Instrument instrument;
    InstrumentDocument doc;
    static final byte[] PDF = "%PDF-1.4\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("library-owner"); buyer = account("library-buyer"); stranger = account("library-stranger"); admin = account("library-admin");
        admin.setRoles(Set.of(Role.ROLE_ADMIN)); users.flush();
        instrument = instruments.saveAndFlush(new Instrument("Library guitar", "Fender", 1987, owner, "Actif"));
        doc = upload(owner, instrument, "Original invoice", "INVOICE");
    }
    @Test void editsPersonalLabelsWithoutRewritingTheFileOrOtherReadersRecords() throws Exception {
        var transfer = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true, List.of(doc.getId()));
        transfers.resolve(buyer, transfer.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(post(path() + "/labels").with(user(buyer.getEmail())).with(csrf()).param("version", "-1")
                        .param("title", "My certificate <script>").param("category", "CERTIFICATE"))
                .andExpect(redirectedUrl(path() + "/manage"));
        assertThat(library.detail(buyer, doc.getId()).title()).isEqualTo("My certificate <script>");
        assertThat(library.detail(owner, doc.getId()).title()).isEqualTo("Original invoice");
        assertThat(doc.getTitle()).isEqualTo("Original invoice"); assertThat(doc.getKind()).isEqualTo(InstrumentDocument.Kind.INVOICE);
        assertThat(uploads.bytes(buyer, doc.getId())).isEqualTo(PDF);
        mvc.perform(get(path() + "/manage").with(user(buyer.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("My certificate &lt;script&gt;")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/transfers/" + transfer.getId()).with(user(buyer.getEmail())))
                .andExpect(content().string(containsString("Original invoice")))
                .andExpect(content().string(not(containsString("My certificate"))));
    }
    @Test void archivingIsPersonalReversibleAndDoesNotRevokeOrAlterPendingTransfers() throws Exception {
        var transfer = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true, List.of(doc.getId()));
        mvc.perform(post(path() + "/archive").with(user(owner.getEmail())).with(csrf()).param("version", "-1").param("archived", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/documents").with(user(owner.getEmail()))).andExpect(model().attribute("documents", hasSize(0)));
        mvc.perform(get("/documents").param("state", "ARCHIVED").with(user(owner.getEmail())))
                .andExpect(model().attribute("documents", hasSize(1)));
        assertThat(uploads.transferable(owner, instrument.getId())).isEmpty();
        mvc.perform(get(path() + "/download").with(user(owner.getEmail()))).andExpect(status().isOk());
        transfers.resolve(buyer, transfer.getId(), InstrumentTransfer.Status.ACCEPTED);
        assertThat(library.detail(buyer, doc.getId()).archived()).isFalse();
        mvc.perform(get("/documents").with(user(buyer.getEmail()))).andExpect(model().attribute("documents", hasSize(1)));
        assertThat(uploads.bytes(buyer, doc.getId())).isEqualTo(PDF);
        library.archive(owner, doc.getId(), library.detail(owner, doc.getId()).version(), false);
        assertThat(library.detail(owner, doc.getId()).archived()).isFalse();
    }
    @Test void archivedDocumentsCannotBeInjectedIntoNewProposals() throws Exception {
        library.archive(owner, doc.getId(), -1, true);
        mvc.perform(post("/instruments/" + instrument.getId() + "/transfers").with(user(owner.getEmail())).with(csrf())
                .param("recipient", buyer.getUsername()).param("confirmed", "true").param("documentIds", doc.getId().toString()))
                .andExpect(status().isNotFound());
    }
    @Test void deletionRequiresConfirmationAndRemovesUnreferencedBlobAndLabels() throws Exception {
        library.edit(owner, doc.getId(), -1, "My spare file", "OTHER");
        long version = library.detail(owner, doc.getId()).version();
        mvc.perform(get(path() + "/delete").with(user(owner.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Permanently delete this upload")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        assertThat(documents.existsById(doc.getId())).isTrue();
        mvc.perform(post(path() + "/delete").with(user(owner.getEmail())).with(csrf()).param("version", Long.toString(version)))
                .andExpect(redirectedUrl("/documents"));
        assertThat(documents.existsById(doc.getId())).isFalse(); assertThat(contents.existsById(doc.getId())).isFalse();
        assertThat(entries.findByDocument_IdAndReader_Id(doc.getId(), owner.getId())).isEmpty();
        mvc.perform(get(path() + "/download").with(user(owner.getEmail()))).andExpect(status().isNotFound());
    }
    @Test void everyTransferReferenceProtectsAgainstDeletionEvenAfterDeclineOrCancellation() throws Exception {
        var transfer = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true, List.of(doc.getId()));
        for (String suffix : List.of("/delete")) {
            mvc.perform(get(path() + suffix).with(user(owner.getEmail()))).andExpect(status().isConflict());
            mvc.perform(post(path() + suffix).with(user(owner.getEmail())).with(csrf()).param("version", "-1"))
                    .andExpect(status().isConflict()).andExpect(content().string(containsString("role=\"alert\"")));
        }
        transfers.resolve(buyer, transfer.getId(), InstrumentTransfer.Status.DECLINED);
        mvc.perform(post(path() + "/delete").with(user(owner.getEmail())).with(csrf()).param("version", "-1"))
                .andExpect(status().isConflict());
        assertThat(documents.existsById(doc.getId())).isTrue(); assertThat(contents.existsById(doc.getId())).isTrue();
        library.archive(owner, doc.getId(), -1, true);
        assertThat(library.detail(owner, doc.getId()).archived()).isTrue();
    }
    @Test void privateReportEvidenceIsRetainedAndPersonalLabelsDoNotRewriteTheReport() throws Exception {
        LuthierProfile p = new LuthierProfile(stranger); p.update("Workshop", "Paris", "Work", "About", "contact", true); workshops.saveAndFlush(p);
        LuthierIntervention i = interventions.saveAndFlush(new LuthierIntervention(instrument, owner, p, LuthierIntervention.Kind.INSPECTION, "Request"));
        var report = assessments.saveAndFlush(new LuthierAssessment(i, stranger, 1, java.time.LocalDate.now(), LuthierAssessment.Conclusion.CONSISTENT, "Findings", "Evidence", "Limits", ""));
        uploads.attach(stranger, report.getId(), "Original proof", "PRIVATE", file());
        var attachment = documents.findByAssessmentIdInOrderByIdAsc(List.of(report.getId())).getFirst();
        library.edit(stranger, attachment.getId(), -1, "Private workshop receipt", "RECEIPT");
        mvc.perform(post("/documents/" + attachment.getId() + "/delete").with(user(stranger.getEmail())).with(csrf()).param("version", "0"))
                .andExpect(status().isConflict());
        mvc.perform(get("/interventions/" + i.getId()).with(user(stranger.getEmail())))
                .andExpect(content().string(containsString("Original proof")))
                .andExpect(content().string(not(containsString("Private workshop receipt"))));
        assertThat(attachment.getKind()).isEqualTo(InstrumentDocument.Kind.EVIDENCE);
        assertThat(library.detail(stranger, attachment.getId()).canDelete()).isFalse();
    }
    @Test void filtersUsePersonalLabelsAndNeverExposeOtherPeoplesFilesOrInstruments() throws Exception {
        library.edit(owner, doc.getId(), -1, "Insurance copy", "CERTIFICATE");
        Instrument hidden = instruments.saveAndFlush(new Instrument("SECRET OTHER INSTRUMENT", "Brand", 2000, stranger, "Actif"));
        upload(stranger, hidden, "SECRET OTHER INVOICE", "INVOICE");
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("q", "insurance").param("category", "CERTIFICATE")
                        .param("instrumentId", instrument.getId().toString()).param("access", "PRIVATE").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(model().attribute("documents", hasSize(1)))
                .andExpect(content().string(not(containsString("SECRET OTHER"))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("q", "SECRET OTHER"))
                .andExpect(model().attribute("documents", hasSize(0))).andExpect(content().string(not(containsString("SECRET OTHER INSTRUMENT"))));
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("category", "INVOICE"))
                .andExpect(model().attribute("documents", hasSize(0)));
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("q", "file.pdf"))
                .andExpect(model().attribute("documents", hasSize(1)));
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("state", "INVALID")).andExpect(status().isBadRequest());
    }
    @Test void staleEditsArchivesAndDeletesCannotOverwriteNewerLibraryChanges() throws Exception {
        library.edit(owner, doc.getId(), -1, "First label", "OTHER");
        mvc.perform(post(path() + "/labels").with(user(owner.getEmail())).with(csrf()).param("version", "-1")
                        .param("title", "Stale label").param("category", "INVOICE"))
                .andExpect(status().isConflict());
        mvc.perform(post(path() + "/archive").with(user(owner.getEmail())).with(csrf()).param("version", "-1").param("archived", "true"))
                .andExpect(status().isConflict());
        mvc.perform(post(path() + "/delete").with(user(owner.getEmail())).with(csrf()).param("version", "-1"))
                .andExpect(status().isConflict());
        assertThat(library.detail(owner, doc.getId()).title()).isEqualTo("First label");
        assertThat(library.detail(owner, doc.getId()).archived()).isFalse();
        mvc.perform(post(path() + "/labels").with(user(owner.getEmail())).with(csrf()).param("version", "0")
                        .param("title", " ").param("category", "INVOICE"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "document.invalid"));
    }
    @Test void paginationPreservesFiltersAndDeletingAnUnusedUploadFreesItsQuotaSlot() throws Exception {
        for (int n = 1; n < 20; n++) upload(owner, instrument, "Invoice " + n, "INVOICE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(owner, instrument, "Over quota", "OTHER"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("document.limit");
        Instrument second = instruments.saveAndFlush(new Instrument("Second library guitar", "Brand", 2000, owner, "Actif"));
        upload(owner, second, "Second invoice", "INVOICE");
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("q", "invoice").param("category", "INVOICE").param("lang", "en"))
                .andExpect(model().attribute("documents", hasSize(20)))
                .andExpect(content().string(containsString("page=1")))
                .andExpect(content().string(containsString("category=INVOICE")));
        mvc.perform(get("/documents").with(user(owner.getEmail())).param("q", "invoice").param("category", "INVOICE").param("page", "1"))
                .andExpect(model().attribute("documents", hasSize(1)));
        library.delete(owner, doc.getId(), -1);
        upload(owner, instrument, "Replacement file", "OTHER");
        assertThat(documents.countByInstrumentIdAndUploaderId(instrument.getId(), owner.getId())).isEqualTo(20);
    }
    @Test void outsidersAdminsAndRecipientsCannotDeleteOtherPeoplesUploads() throws Exception {
        for (User actor : List.of(stranger, admin)) {
            mvc.perform(get(path() + "/manage").with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(post(path() + "/labels").with(user(actor.getEmail())).with(csrf()).param("version", "-1").param("title", "Forged").param("category", "OTHER"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(path() + "/archive").with(user(actor.getEmail())).with(csrf()).param("version", "-1").param("archived", "true"))
                    .andExpect(status().isNotFound());
        }
        var transfer = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true, List.of(doc.getId()));
        transfers.resolve(buyer, transfer.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(post(path() + "/delete").with(user(buyer.getEmail())).with(csrf()).param("version", "-1"))
                .andExpect(status().isNotFound());
        mvc.perform(post(path() + "/archive").with(user(owner.getEmail())).param("version", "-1").param("archived", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path() + "/manage")).andExpect(redirectedUrl("/login"));
    }
    private String path() { return "/documents/" + doc.getId(); }
    private MockMultipartFile file() { return new MockMultipartFile("file", "file.pdf", "application/pdf", PDF); }
    private InstrumentDocument upload(User actor, Instrument target, String title, String kind) {
        uploads.upload(actor, target.getId(), title, kind, file());
        return documents.transferableDocuments(target.getId(), actor.getId()).getFirst();
    }
    private User account(String name) { User u = new User(); u.setUsername(name); u.setEmail(name + "@example.org"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
}
