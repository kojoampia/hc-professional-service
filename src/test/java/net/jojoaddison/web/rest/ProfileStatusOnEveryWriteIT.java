package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * <b>Every write that can move a published field announces it</b> — backlog.md item 49.
 *
 * <p>Item 47 § 2b hung {@code ProfileStatus} off a table of four call sites and the table read as
 * though it were exhaustive. It was not. {@code POST /api/onboarding/documents} — the path a
 * clinician renews their own licence by — was missing, and so was the whole
 * {@code PersonalDocumentResource} CRUD surface. An upload adds a {@code PENDING} row, so
 * {@code isVerified} went <em>true to false on the server</em> while hc-admin's directory went on
 * rendering "verified" until an administrator happened to touch something else on that profile.
 *
 * <p><b>Why this class is an integration test and not a unit one.</b> The defect is not in any
 * method's logic; it is in which methods were remembered. Mocking {@code OnboardingService} and
 * asserting it was called proves only that the caller written today calls what it was written to
 * call — which is precisely the test that was already green while the defect was live. These go
 * through the real HTTP surfaces and the real persistence, so a path that stops announcing fails
 * here whatever the reason.
 *
 * <p><b>{@link #anyPathThatPersistsAnnounces} is the one that generalises</b>, and it is the reason
 * the fix is a listener rather than four more calls. It writes through the repositories directly,
 * naming no resource and no service: a fifth mutating path written next month reaches the database
 * the same way, so it publishes without its author knowing anything about this. A test that only
 * enumerated today's endpoints would pass that path silently, which is the mistake being fixed
 * rather than a fix.
 *
 * <p><b>The known hole, stated rather than asserted:</b> a query-based update
 * ({@code MongoTemplate.updateFirst}/{@code updateMulti}/{@code findAndModify}) raises no save event
 * and would be silent. Nothing writes these three collections that way today. See
 * {@code ProfileStatusAnnouncer}'s javadoc for the full list of what the mechanism does not cover.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ProfileStatusOnEveryWriteIT {

    private static final String CLINICIAN = "renewing-clinician";
    private static final byte[] PDF_BYTES = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    /**
     * The far side of the announcement, mocked so that "was anything published, and what did it say"
     * is answerable without a broker. Its other methods are unstubbed and return void, which is what
     * the real publisher does when Kafka is disabled.
     */
    @MockitoBean
    private DomainEventPublisher events;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(CLINICIAN));
        clearInvocations(events);
    }

    @AfterEach
    void cleanup() {
        personalDocumentRepository.deleteAll();
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        eventRepository.deleteAll();
    }

    /**
     * <b>The reported defect.</b> A verified clinician uploads a renewed licence; the new row is
     * PENDING, so the server no longer considers them verified. Before item 49 this path published
     * nothing at all, and the directory on the other stack kept saying "verified" — the failure this
     * service's own code calls the worse half of the two.
     */
    @Test
    @WithMockUser(username = CLINICIAN, authorities = { "ROLE_USER" })
    void renewingALicenceAnnouncesThatTheClinicianIsNoLongerVerified() throws Exception {
        personalDocumentRepository.save(
            new PersonalDocument()
                .profileId(profile.getId())
                .name("licence-2025.pdf")
                .type(DocumentType.LICENSE)
                .expiryDate(LocalDate.now().plusWeeks(2))
                .verificationStatus(VerificationStatus.VERIFIED)
        );
        clearInvocations(events);

        upload(DocumentType.LICENSE, "licence-2026.pdf", LocalDate.now().plusYears(1), null);

        assertThat(announcedVerifiedFlags()).as("one frame, saying the clinician is no longer verified").containsExactly(false);
    }

    /**
     * The same journey with {@code supersedesDocumentId} set, which saves twice — the replacement,
     * then the archived row.
     *
     * <p>Two assertions in one, and the second is why the announcement is deferred to the end of the
     * request rather than made per save: <b>exactly one frame</b>, and it reports the state after
     * <em>both</em> writes. A frame sent between them would have described a moment nobody asked
     * for.
     */
    @Test
    @WithMockUser(username = CLINICIAN, authorities = { "ROLE_USER" })
    void aSupersedingRenewalAnnouncesOnceAndAfterBothWrites() throws Exception {
        upload(DocumentType.LICENSE, "licence-2025.pdf", LocalDate.now().plusWeeks(2), null);
        personalDocumentRepository.save(byName("licence-2025.pdf").verificationStatus(VerificationStatus.VERIFIED));
        clearInvocations(events);

        uploadReplacing(
            DocumentType.LICENSE,
            "licence-2026.pdf",
            LocalDate.now().plusYears(1),
            null,
            byName("licence-2025.pdf").getId()
        ).andExpect(status().isCreated());

        assertThat(announcedVerifiedFlags()).containsExactly(false);
    }

    /**
     * The generated CRUD surface, all four verbs. It is an administrative data-maintenance surface
     * rather than the clinician's own, and every one of its writes moves what the directory renders;
     * none of the four announced anything before item 49.
     */
    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void everyPersonalDocumentCrudWriteAnnounces() throws Exception {
        String created = restMockMvc
            .perform(
                post("/api/personal-documents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"profileId\":\"" +
                        profile.getId() +
                        "\",\"name\":\"certificate.pdf\",\"type\":\"CERTIFICATE\"," +
                        "\"verificationStatus\":\"PENDING\"}"
                    )
            )
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(announcedVerifiedFlags()).as("POST").containsExactly(false);
        String id = byName("certificate.pdf").getId();
        assertThat(created).contains(id);

        clearInvocations(events);
        restMockMvc
            .perform(
                put("/api/personal-documents/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"id\":\"" +
                        id +
                        "\",\"profileId\":\"" +
                        profile.getId() +
                        "\",\"name\":\"certificate.pdf\"," +
                        "\"type\":\"CERTIFICATE\",\"verificationStatus\":\"VERIFIED\"}"
                    )
            )
            .andExpect(status().isOk());
        assertThat(announcedVerifiedFlags()).as("PUT — and the verdict it wrote is on the frame").containsExactly(true);

        clearInvocations(events);
        restMockMvc
            .perform(
                patch("/api/personal-documents/" + id)
                    .contentType("application/merge-patch+json")
                    .content("{\"id\":\"" + id + "\",\"name\":\"certificate-rescanned.pdf\"}")
            )
            .andExpect(status().isOk());
        assertThat(announcedVerifiedFlags()).as("PATCH").containsExactly(true);

        clearInvocations(events);
        restMockMvc.perform(delete("/api/personal-documents/" + id)).andExpect(status().isNoContent());
        // isVerified is false again: the profile now has no live document at all, and "at least one
        // present" is half of what the flag means.
        assertThat(announcedVerifiedFlags()).as("DELETE").containsExactly(false);
    }

    /**
     * {@code PUT /api/notifications/preferences} is an audited save — it moves {@code modifiedDate}
     * and {@code lastModifiedBy}, both rendered on hc-admin's console — and it announced nothing.
     *
     * <p>The second half is the sharper one: <b>it creates a profile when the account has none</b>,
     * so before item 49 a profile could come into being that the estate never heard about.
     */
    @Test
    void writingPushPreferencesAnnounces() throws Exception {
        restMockMvc
            .perform(
                put("/api/notifications/preferences")
                    .with(user(CLINICIAN).authorities(new SimpleGrantedAuthority("ROLE_CARER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"messages\":false,\"compliance\":true,\"showSenderName\":false}")
            )
            .andExpect(status().isOk());
        assertThat(announcedProfileIds()).containsExactly(profile.getId());

        clearInvocations(events);
        restMockMvc
            .perform(
                put("/api/notifications/preferences")
                    .with(user("clinician-with-no-profile-yet").authorities(new SimpleGrantedAuthority("ROLE_CARER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"messages\":true,\"compliance\":true,\"showSenderName\":true}")
            )
            .andExpect(status().isOk());
        Profile born = profileRepository.findByAccountId("clinician-with-no-profile-yet").orElseThrow();
        assertThat(announcedProfileIds()).as("a profile that came into being here is announced").containsExactly(born.getId());
    }

    /**
     * Assigning an organisation writes the profile <em>and</em> the application in one request. Both
     * are documents the announcement reads, so a naive per-save hook would send two frames for one
     * decision; this is the dedupe, asserted on the path that has it.
     */
    @Test
    void assigningAnOrganisationAnnouncesOnce() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(CLINICIAN, OnboardingStatus.APPROVED).profileId(profile.getId())
        );
        clearInvocations(events);

        restMockMvc
            .perform(
                put("/api/onboarding/applications/" + application.getId() + "/organization")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"specialtyCategoryId\":\"cardiology\"}")
            )
            .andExpect(status().isOk());

        assertThat(announcedProfileIds()).containsExactly(profile.getId());
    }

    /**
     * <b>The test a list of call sites cannot be written to pass.</b>
     *
     * <p>It names no resource, no service and no handler — it persists the two documents whose state
     * the frame reports, which is the only thing a mutating path anywhere in this service must do to
     * change what hc-admin should be told. That is what makes it a statement about the mechanism
     * rather than an inventory of today's endpoints, and it is the reason the fix is a listener: the
     * fifth path, whenever somebody writes it, is covered before it is written.
     */
    @Test
    void anyPathThatPersistsAnnounces() {
        profileRepository.save(profile.firstName("Renamed"));
        assertThat(announcedProfileIds()).as("a bare profile save, through no endpoint at all").containsExactly(profile.getId());

        clearInvocations(events);
        personalDocumentRepository.save(
            new PersonalDocument()
                .profileId(profile.getId())
                .name("out-of-band.pdf")
                .type(DocumentType.CERTIFICATE)
                .verificationStatus(VerificationStatus.PENDING)
        );
        assertThat(announcedProfileIds()).as("a bare document save").containsExactly(profile.getId());

        // Starting an application moves isComplete too — consent is the first of the eight
        // requirements and it lives on the application, not on the profile or on any document.
        clearInvocations(events);
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(CLINICIAN, OnboardingStatus.APPLICATION_STARTED).profileId(profile.getId())
        );
        assertThat(announcedProfileIds()).as("a bare application save").containsExactly(profile.getId());
    }

    // ------------------------------------------------------------------ helpers

    /** What was announced about this profile, in order — one entry per frame. */
    private java.util.List<String> announcedProfileIds() {
        ArgumentCaptor<String> profileId = ArgumentCaptor.forClass(String.class);
        verify(events, times(publications())).publishProfileStatus(
            any(),
            profileId.capture(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            any()
        );
        return profileId.getAllValues();
    }

    /** The {@code isVerified} each frame carried, in order. */
    private java.util.List<Boolean> announcedVerifiedFlags() {
        ArgumentCaptor<Boolean> verified = ArgumentCaptor.forClass(Boolean.class);
        verify(events, times(publications())).publishProfileStatus(any(), any(), anyBoolean(), verified.capture(), any(), any(), any());
        return verified.getAllValues();
    }

    /**
     * How many frames were sent, read off the mock rather than assumed.
     *
     * <p>Captors need a matching {@code times(n)}; asking for the count first lets the assertions
     * above be about <em>what</em> was announced and how often, in one AssertJ line, instead of a
     * bare {@code verify} that says nothing when it fails.
     */
    private int publications() {
        return (int) org.mockito.Mockito.mockingDetails(events)
            .getInvocations()
            .stream()
            .filter(invocation -> "publishProfileStatus".equals(invocation.getMethod().getName()))
            .count();
    }

    private static RequestPostProcessor admin() {
        return user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private PersonalDocument byName(String name) {
        return personalDocumentRepository
            .findByProfileId(profile.getId())
            .stream()
            .filter(document -> name.equals(document.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No document named " + name));
    }

    private void upload(DocumentType type, String filename, LocalDate expiryDate, String otherLabel) throws Exception {
        uploadReplacing(type, filename, expiryDate, otherLabel, null).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions uploadReplacing(
        DocumentType type,
        String filename,
        LocalDate expiryDate,
        String otherLabel,
        String supersedesDocumentId
    ) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/onboarding/documents")
            .file(new MockMultipartFile("file", filename, MediaType.APPLICATION_PDF_VALUE, PDF_BYTES))
            .param("type", type.name());
        if (expiryDate != null) {
            builder.param("expiryDate", expiryDate.toString());
        }
        if (otherLabel != null) {
            builder.param("otherLabel", otherLabel);
        }
        if (supersedesDocumentId != null) {
            builder.param("supersedesDocumentId", supersedesDocumentId);
        }
        return restMockMvc.perform(builder);
    }
}
