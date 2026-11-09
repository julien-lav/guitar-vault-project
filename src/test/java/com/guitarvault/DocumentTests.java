package com.guitarvault;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
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
class DocumentTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired InstrumentRepository instruments;
    @Autowired InstrumentDocumentRepository documents;
    @Autowired DocumentContentRepository contents;
    @Autowired DocumentGrantRepository grants;
    @Autowired DocumentService service;
    @Autowired InstrumentTransferService transfers;
    @Autowired InstrumentTransferRepository transferRecords;
    @Autowired LuthierProfileRepository workshops;
    @Autowired LuthierInterventionRepository interventions;
    @Autowired LuthierAssessmentRepository assessments;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User owner, buyer, next, luthier, admin;
    Instrument instrument;
    LuthierAssessment report;
    // Tiny PDF fixture; PDF syntax is not a security boundary: all downloads use attachment + nosniff.
    static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("docs-owner"); buyer = account("docs-buyer"); next = account("docs-next"); luthier = account("docs-luthier"); admin = account("docs-admin");
        admin.setRoles(java.util.Set.of(Role.ROLE_ADMIN)); users.flush();
        instrument = instruments.saveAndFlush(new Instrument("Document passport", "Fender", 1987, owner, "Actif"));
        LuthierProfile p = new LuthierProfile(luthier); p.update("Document workshop", "Paris", "Inspection", "About", "Contact", true); workshops.saveAndFlush(p);
        LuthierIntervention i = interventions.saveAndFlush(new LuthierIntervention(instrument, owner, p, LuthierIntervention.Kind.INSPECTION, "Private request"));
        report = assessments.saveAndFlush(new LuthierAssessment(i, luthier, 1, java.time.LocalDate.now(), LuthierAssessment.Conclusion.CONSISTENT, "Findings", "Observed", "Limits", ""));
        i.complete(); interventions.flush();
    }
    @Test void filesArePrivateByDefaultAndDownloadsAreProtectedAttachments() throws Exception {
        mvc.perform(multipart(path()).file(pdf("Invoice.pdf")).with(user(owner.getEmail())).with(csrf())
                .param("title", "Private invoice <script>").param("kind", "INVOICE").param("visibility", "REPORT").param("uploaderId", buyer.getId().toString()))
                .andExpect(redirectedUrl(path()));
        var doc = documents.personalDocuments(owner.getId()).getFirst();
        assertThat(doc.getVisibility()).isEqualTo(InstrumentDocument.Visibility.PRIVATE);
        assertThat(doc.getUploader().getId()).isEqualTo(owner.getId());
        mvc.perform(get(path()).with(user(owner.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Private invoice &lt;script&gt;")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get(download(doc)).with(user(owner.getEmail())))
                .andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox")));
        for (User actor : List.of(buyer, next, luthier, admin)) {
            mvc.perform(get(download(doc)).with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(get("/documents").with(user(actor.getEmail())))
                    .andExpect(content().string(not(containsString("Private invoice"))));
        }
    }
    @Test void uploadsInferImageTypeAndRejectFakeTypesOversizeAndInvalidNames() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        mvc.perform(multipart(path()).file(new MockMultipartFile("file", "photo.png", "text/html", png.toByteArray()))
                .with(user(owner.getEmail())).with(csrf()).param("title", "Certificate photo").param("kind", "CERTIFICATE"))
                .andExpect(status().is3xxRedirection());
        assertThat(documents.personalDocuments(owner.getId()).getFirst().getContentType()).isEqualTo("image/png");
        for (var file : List.of(new MockMultipartFile("file", "evil.html", "application/pdf", PDF),
                new MockMultipartFile("file", "fake.pdf", "application/pdf", "<html>fake</html>".getBytes()),
                new MockMultipartFile("file", "fake.png", "image/png", PDF),
                new MockMultipartFile("file", "invoice.pdf\r\nInjected", "application/pdf", PDF),
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]),
                new MockMultipartFile("file", "huge.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]))) {
            mvc.perform(multipart(path()).file(file).with(user(owner.getEmail())).with(csrf()).param("title", "Keep this title").param("kind", "INVOICE"))
                    .andExpect(status().isOk()).andExpect(model().attribute("error", "document.fileError"))
                    .andExpect(content().string(containsString("Keep this title")));
        }
        assertThat(documents.count()).isEqualTo(1); assertThat(contents.count()).isEqualTo(1);
    }
    @Test void selectedFilesBecomeReadableOnlyOnAcceptanceAndNeverShareUnselectedInvoices() throws Exception {
        var invoice = upload("Never shared", "INVOICE"); var certificate = upload("Selected certificate", "CERTIFICATE");
        mvc.perform(get("/instruments/" + instrument.getId() + "/transfers/new").with(user(owner.getEmail())).param("lang", "en"))
                .andExpect(content().string(containsString("name=\"documentIds\"")))
                .andExpect(content().string(not(containsString("checked=\"checked\""))));
        var t = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true, List.of(certificate.getId()));
        var lateInvoice = upload("Uploaded after proposal", "INVOICE");
        mvc.perform(get(download(certificate)).with(user(buyer.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/transfers/" + t.getId()).with(user(buyer.getEmail())))
                .andExpect(content().string(not(containsString("Selected certificate"))));
        transfers.resolve(buyer, t.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get(download(certificate)).with(user(buyer.getEmail()))).andExpect(status().isOk()).andExpect(content().bytes(PDF));
        mvc.perform(get(download(invoice)).with(user(buyer.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(download(lateInvoice)).with(user(buyer.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/documents").with(user(owner.getEmail()))).andExpect(content().string(containsString("Never shared")));
        mvc.perform(get(path()).with(user(buyer.getEmail())))
                .andExpect(content().string(containsString("Selected certificate"))).andExpect(content().string(not(containsString("Never shared"))));
        var again = transfers.propose(buyer, instrument.getId(), next.getUsername(), true);
        assertThat(again.getDocuments()).isEmpty();
        transfers.resolve(next, again.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get(download(certificate)).with(user(next.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(download(certificate)).with(user(buyer.getEmail()))).andExpect(status().isOk());
        mvc.perform(get(download(certificate)).with(user(owner.getEmail()))).andExpect(status().isOk());
    }
    @Test void cancelledAndDeclinedProposalsGrantNothingAndForgedSelectionsAreRejected() throws Exception {
        var doc = upload("Certificate", "CERTIFICATE");
        for (var decision : List.of(InstrumentTransfer.Status.CANCELLED, InstrumentTransfer.Status.DECLINED)) {
            var t = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true, List.of(doc.getId()));
            transfers.resolve(decision == InstrumentTransfer.Status.CANCELLED ? owner : buyer, t.getId(), decision);
            mvc.perform(get(download(doc)).with(user(buyer.getEmail()))).andExpect(status().isNotFound());
        }
        assertThat(grants.count()).isZero();
        Instrument other = instruments.saveAndFlush(new Instrument("Other", "Brand", 2000, owner, "Actif"));
        service.upload(owner, other.getId(), "Unrelated", "INVOICE", pdf("other.pdf"));
        var unrelated = documents.transferableDocuments(other.getId(), owner.getId()).getFirst();
        mvc.perform(post("/instruments/" + instrument.getId() + "/transfers").with(user(owner.getEmail())).with(csrf())
                .param("recipient", buyer.getUsername()).param("confirmed", "true").param("documentIds", unrelated.getId().toString()))
                .andExpect(status().isNotFound());
        assertThat(transferRecords.findBySenderIdOrRecipientIdOrderByIdDesc(owner.getId(), owner.getId())).hasSize(2);
    }
    @Test void evidenceScopesSeparatePrivateInvoicesOriginalRequestsAndFutureReportReaders() throws Exception {
        service.attach(luthier, report.getId(), "Luthier private invoice", "PRIVATE", pdf("private.pdf"));
        service.attach(luthier, report.getId(), "Requester receipt", "REQUEST", pdf("request.pdf"));
        service.attach(luthier, report.getId(), "Shared examination", "REPORT", pdf("evidence.pdf"));
        var files = documents.findByAssessmentIdInOrderByIdAsc(List.of(report.getId()));
        var privateFile = files.get(0); var requestFile = files.get(1); var sharedFile = files.get(2);
        mvc.perform(get(download(privateFile)).with(user(owner.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(download(requestFile)).with(user(owner.getEmail()))).andExpect(status().isOk());
        mvc.perform(get(download(sharedFile)).with(user(owner.getEmail()))).andExpect(status().isOk());
        mvc.perform(get("/interventions/" + report.getIntervention().getId()).with(user(owner.getEmail())))
                .andExpect(content().string(containsString("Requester receipt")))
                .andExpect(content().string(not(containsString("Luthier private invoice"))));
        var t = transfers.propose(owner, instrument.getId(), buyer.getUsername(), true);
        transfers.resolve(buyer, t.getId(), InstrumentTransfer.Status.ACCEPTED);
        mvc.perform(get("/instruments/" + instrument.getId() + "/passport-reports").with(user(buyer.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Shared examination")))
                .andExpect(content().string(not(containsString("Luthier private invoice"))))
                .andExpect(content().string(not(containsString("Requester receipt"))))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get(download(sharedFile)).with(user(buyer.getEmail()))).andExpect(status().isOk());
        mvc.perform(get(download(requestFile)).with(user(buyer.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(download(privateFile)).with(user(buyer.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(download(sharedFile)).with(user(admin.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get(download(privateFile)).with(user(luthier.getEmail()))).andExpect(status().isOk());
        assertThat(documents.findById(sharedFile.getId()).orElseThrow().getAssessment().getRevision()).isEqualTo(1);
    }
    @Test void attachmentUploadUsesAuthorOnlyAndDefaultsToPrivateWithUsefulErrors() throws Exception {
        String path = "/assessments/" + report.getId() + "/attachments";
        mvc.perform(get(path + "/new").with(user(luthier.getEmail())).param("lang", "en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Only me")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(multipart(path).file(pdf("evidence.pdf")).with(user(luthier.getEmail())).with(csrf()).param("title", "Private default"))
                .andExpect(redirectedUrl("/interventions/" + report.getIntervention().getId()));
        assertThat(documents.findByAssessmentIdInOrderByIdAsc(List.of(report.getId())).getFirst().getVisibility()).isEqualTo(InstrumentDocument.Visibility.PRIVATE);
        mvc.perform(multipart(path).file(pdf("evidence.pdf")).with(user(luthier.getEmail())).with(csrf()).param("title", "Keep title").param("visibility", "PUBLIC"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", "document.invalid"))
                .andExpect(content().string(containsString("Keep title")));
        for (User actor : List.of(owner, buyer, admin)) {
            mvc.perform(get(path + "/new").with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(multipart(path).file(pdf("forged.pdf")).with(user(actor.getEmail())).with(csrf()).param("title", "Forged"))
                    .andExpect(status().isNotFound());
        }
    }
    @Test void missingFilesInvalidCategoriesAndUnauthorizedUploadsDoNotWritePartialData() throws Exception {
        mvc.perform(multipart(path()).with(user(owner.getEmail())).with(csrf()).param("title", "Missing file").param("kind", "INVOICE"))
                .andExpect(model().attribute("error", "document.fileError"));
        for (String title : List.of(" ", "x".repeat(151)))
            mvc.perform(multipart(path()).file(pdf("invoice.pdf")).with(user(owner.getEmail())).with(csrf()).param("title", title).param("kind", "INVOICE"))
                    .andExpect(model().attribute("error", "document.invalid"));
        mvc.perform(multipart(path()).file(pdf("invoice.pdf")).with(user(owner.getEmail())).with(csrf()).param("title", "Title").param("kind", "EVIDENCE"))
                .andExpect(model().attribute("error", "document.invalid"));
        mvc.perform(multipart(path()).file(pdf("invoice.pdf")).with(user(buyer.getEmail())).with(csrf()).param("title", "Forged"))
                .andExpect(status().isNotFound());
        mvc.perform(multipart(path()).file(pdf("invoice.pdf")).with(user(owner.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get("/documents")).andExpect(redirectedUrl("/login"));
        assertThat(documents.count()).isZero(); assertThat(contents.count()).isZero();
    }
    private InstrumentDocument upload(String title, String kind) {
        service.upload(owner, instrument.getId(), title, kind, pdf("upload.pdf"));
        return documents.personalDocuments(owner.getId()).getFirst();
    }
    private String path() { return "/instruments/" + instrument.getId() + "/documents"; }
    private String download(InstrumentDocument doc) { return "/documents/" + doc.getId() + "/download"; }
    private MockMultipartFile pdf(String name) { return new MockMultipartFile("file", name, "application/pdf", PDF); }
    private User account(String name) { User u = new User(); u.setUsername(name); u.setEmail(name + "@example.org"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
}
