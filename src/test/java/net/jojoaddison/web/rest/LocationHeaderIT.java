package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.AbsenceRepository;
import net.jojoaddison.repository.CategoryRepository;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The {@code Location} header must name a URL the caller can actually follow (backlog.md item 41).
 *
 * <p><b>The defect.</b> Every {@code 201} in this service built its {@code Location} by hand —
 * {@code new URI("/api/profiles/" + id)} — in eleven resources. An absolute-path reference resolves
 * against the <i>origin</i> of the effective request URI, so a client that POSTed
 * {@code https://professional.abofonsa.com/services/professionalservice/api/profiles} was told the
 * new resource lives at {@code https://professional.abofonsa.com/api/profiles/{id}}, which 404s. The
 * gateway routes with {@code StripPrefix=2} and nothing put the prefix back.
 *
 * <p><b>Why item 31 did not already fix it.</b> Item 31 registered {@code ForwardedHeaderFilter} and
 * that fixed the {@code Link} header on the paginated reads. The filter rewrites URLs only through
 * {@code sendRedirect}, and there is no {@code sendRedirect} in {@code src/main/java} — a
 * {@code Location} set directly on the response was passed through untouched. Same defect, same
 * cause, different response header; see {@link PaginationLinkHeaderIT} for the first half.
 *
 * <p><b>Every assertion here reads the header's value.</b> That is the reusable lesson from item 31:
 * {@code PatientResourceIT} and {@code DutyRosterVisitPrivacyIT} assert {@code header().exists} and
 * stop, and a header that exists and is wrong looks exactly like one that exists and is right. The
 * generated {@code *ResourceIT} classes assert {@code status().isCreated()} and never look at
 * {@code Location} at all, which is why eleven wrong values shipped.
 *
 * <p><b>Both directions, deliberately.</b> Behind the gateway the stripped prefix must come back; on
 * a direct call — how these tests reach the service, and how anything inside the compose network
 * reaches it — there is no prefix and the bare path is the right answer. A fix that hardcoded
 * {@code /services/professionalservice} would pass the first half and break the second.
 */
@IntegrationTest
@AutoConfigureMockMvc
class LocationHeaderIT {

    /** What the gateway strips, and therefore what it reports in {@code X-Forwarded-Prefix}. */
    private static final String GATEWAY_PREFIX = "/services/professionalservice";

    private static final String EXTERNAL_HOST = "professional.abofonsa.com";

    private static final String NURSE = "location-header-nurse";

    /**
     * The collection endpoints whose {@code POST} takes a body needing nothing but an empty object.
     *
     * <p>Nine of the eleven. Each is a generated resource that saves straight to its repository with
     * no {@code @Valid} and no cross-entity reference, so {@code &#123;&#125;} is a valid create —
     * which is what makes them worth driving as one parameterized case rather than eleven copies.
     * {@code /api/absences} and {@code /api/duty-roster} need real fixtures and get their own tests
     * below, so all eleven are covered.
     */
    static final List<String> SIMPLE_CREATES = List.of(
        "/api/profiles",
        "/api/personal-documents",
        "/api/teams",
        "/api/addresses",
        "/api/metadata",
        "/api/reports",
        "/api/activities",
        "/api/categories",
        "/api/tasks"
    );

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private AbsenceRepository absenceRepository;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    /**
     * Categories and teams are cleaned up here rather than by their own {@code DELETE}, which item 57
     * removed — see {@link #NO_DELETE}. Profiles were already in this list for the same reason.
     */
    @AfterEach
    void cleanup() {
        absenceRepository.deleteAll();
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
        categoryRepository.deleteAll();
        teamRepository.deleteAll();
    }

    /** A request carrying exactly what Spring Cloud Gateway and the edge nginx put on the wire. */
    private static MockHttpServletRequestBuilder asRelayedByTheGateway(MockHttpServletRequestBuilder request) {
        return request
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", EXTERNAL_HOST)
            .header("X-Forwarded-Prefix", GATEWAY_PREFIX);
    }

    private static MockHttpServletRequestBuilder create(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The {@code Location} and the id the body reports, which must agree. */
    private record Created(String location, String id) {}

    private Created createdBy(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = restMockMvc.perform(request).andExpect(status().isCreated()).andReturn();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).as("a 201 must carry a Location").isNotBlank();
        JsonNode body = om.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("id").asText()).as("the created entity must report its id").isNotBlank();
        return new Created(location, body.path("id").asText());
    }

    /**
     * <b>Follow it.</b> Asserting the string and stopping is how this defect survived in the first
     * place, one level up: {@code PatientResourceIT} asserted {@code header().exists("Link")} and
     * never read the value. Asserting the value and never following it is the same mistake with a
     * longer stride — and it hid a real one. The review of this change found that
     * {@code POST /api/duty-roster} advertised {@code /api/duty-roster/{id}} while
     * {@code DutyRosterResource} had no {@code GET /{id}} at all, so ten of eleven endpoints were
     * fixed and the eleventh's failure merely moved: right prefix, right host, still a 404. Every
     * {@code Location} asserted in this class is now dereferenced.
     *
     * <p>The local path rather than the advertised absolute URL, because MockMvc dispatches against
     * this application and cannot resolve {@code professional.abofonsa.com}. That is exactly the
     * split this file is about: the string assertion proves the value a client would see, and this
     * proves the resource is there to be seen. Neither implies the other.
     */
    private void followable(String path, String id) throws Exception {
        restMockMvc.perform(get(path + "/" + id)).andExpect(status().is2xxSuccessful());
    }

    /**
     * The header a client can follow, on nine creates at once.
     *
     * <p>Run against the unfixed service each of these fails with a bare {@code /api/profiles/{id}} —
     * no scheme, no host, no prefix.
     */
    @ParameterizedTest
    @FieldSource("SIMPLE_CREATES")
    @WithMockUser(username = "location-header-admin", authorities = { "ROLE_ADMIN" })
    void behindTheGatewayEveryLocationCarriesTheStrippedPrefix(String path) throws Exception {
        Created created = createdBy(asRelayedByTheGateway(create(path, "{}")));

        assertThat(created.location()).isEqualTo("https://" + EXTERNAL_HOST + GATEWAY_PREFIX + path + "/" + created.id());
        followable(path, created.id());

        removeCreatedRow(path, created.id());
    }

    /**
     * A direct call is unchanged, which is the half a hardcoded prefix would have broken.
     *
     * <p>With no {@code X-Forwarded-*} on the request the filter is a no-op, and the absolute URL is
     * built from the connection the caller actually used. Still an improvement on the old value: it
     * carries scheme and host, where the hand-built string carried neither.
     */
    @ParameterizedTest
    @FieldSource("SIMPLE_CREATES")
    @WithMockUser(username = "location-header-admin", authorities = { "ROLE_ADMIN" })
    void aDirectCallAdvertisesTheBarePath(String path) throws Exception {
        Created created = createdBy(create(path, "{}"));

        assertThat(created.location()).isEqualTo("http://localhost" + path + "/" + created.id());
        followable(path, created.id());

        removeCreatedRow(path, created.id());
    }

    /**
     * The three collections whose generated {@code DELETE} was removed because it orphaned the rows
     * pointing at it — {@code /api/profiles} by backlog item 56, the other two by item 57.
     *
     * <p>A profile delete orphaned six collections and could announce nothing, since none of
     * {@code ProfileStatus}'s seven contracted fields can say "gone". A category delete left every
     * {@code Profile.specialtyCategoryId} naming a discipline that resolves to nothing; a team delete
     * left {@code Profile.teamIds} and {@code Task.teamId} pointing at the same nothing.
     */
    private static final List<String> NO_DELETE = List.of("/api/profiles", "/api/categories", "/api/teams");

    /**
     * Housekeeping, and three assertions that are not housekeeping.
     *
     * <p>Six of the nine paths clean up through their own {@code DELETE}, and asserting 2xx keeps that
     * honest. The three in {@link #NO_DELETE} assert <b>405</b> rather than skipping the call: a
     * regeneration that quietly restores one of those mappings fails here as well as in that
     * resource's own IT, which is two independent guards on the same fact.
     *
     * <p>405 rather than 404 because the path pattern still matches GET/PUT/PATCH — Spring rejects
     * the method, not the route. Their rows are cleaned up by {@code @AfterEach} instead, which is
     * where the two repositories added for item 57 come in; for profiles that was already true, and
     * is why the delete was redundant there even before the endpoint went.
     */
    private void removeCreatedRow(String path, String id) throws Exception {
        if (NO_DELETE.contains(path)) {
            restMockMvc.perform(delete(path + "/" + id)).andExpect(status().isMethodNotAllowed());
            return;
        }
        restMockMvc.perform(delete(path + "/" + id)).andExpect(status().is2xxSuccessful());
    }

    /**
     * The tenth: requesting leave, which a clinician does for themselves.
     *
     * <p>{@code AbsenceResource} is hand-written rather than generated and was wrong in exactly the
     * same way, which is the point — the defect was not a generator artefact.
     */
    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void requestingLeaveAdvertisesAFollowableLocation() throws Exception {
        profileRepository.save(new Profile().accountId(NURSE).firstName("Ab").lastName("Sent"));
        String body =
            "{\"fromDate\":\"%s\",\"toDate\":\"%s\",\"type\":\"HOLIDAY\"}".formatted(
                    LocalDate.now().plusDays(10),
                    LocalDate.now().plusDays(12)
                );

        Created created = createdBy(asRelayedByTheGateway(create("/api/absences", body)));

        assertThat(created.location()).isEqualTo("https://" + EXTERNAL_HOST + GATEWAY_PREFIX + "/api/absences/" + created.id());
        followable("/api/absences", created.id());
    }

    /**
     * The eleventh: assigning a shift, which is admin-only and {@code @Valid}.
     *
     * <p>Also the one whose collection path is singular — {@code /api/duty-roster}, not
     * {@code /api/duty-rosters} — so it is the case that would catch a fix which derived the path
     * from the entity name rather than from the request. {@code mobile/} calling the pluralised path
     * is a live production defect for the same reason (see {@code web-mobile-port.md} § Phase 0).
     */
    @Test
    @WithMockUser(username = "location-header-admin", authorities = { "ROLE_ADMIN" })
    void assigningAShiftAdvertisesAFollowableLocation() throws Exception {
        Profile nurse = profileRepository.save(new Profile().accountId(NURSE).firstName("On").lastName("Duty"));
        String body =
            "{\"date\":\"%s\",\"duty\":\"NURSE\",\"professionalId\":\"%s\",\"shift\":\"DAY\",\"name\":\"Ward 3\"}".formatted(
                    LocalDate.now().plusDays(3),
                    nurse.getId()
                );

        Created created = createdBy(asRelayedByTheGateway(create("/api/duty-roster", body)));

        assertThat(created.location()).isEqualTo("https://" + EXTERNAL_HOST + GATEWAY_PREFIX + "/api/duty-roster/" + created.id());
        followable("/api/duty-roster", created.id());
    }

    /**
     * A query string on the create does not travel into the identity of what was created.
     *
     * <p>{@code ServletUriComponentsBuilder.fromCurrentRequest()} keeps the query and
     * {@code fromCurrentRequestUri()} does not; {@link net.jojoaddison.web.rest.util.LocationUri}
     * uses the latter, and the difference is invisible until someone posts with a parameter on the
     * URL. Asserted rather than left to the reader, because the two method names differ by four
     * characters and either compiles.
     */
    @Test
    @WithMockUser(username = "location-header-admin", authorities = { "ROLE_ADMIN" })
    void aQueryStringOnTheCreateIsNotCarriedIntoTheLocation() throws Exception {
        Created created = createdBy(asRelayedByTheGateway(create("/api/teams?notify=true", "{}")));

        assertThat(created.location()).isEqualTo("https://" + EXTERNAL_HOST + GATEWAY_PREFIX + "/api/teams/" + created.id());
        followable("/api/teams", created.id());

        removeCreatedRow("/api/teams", created.id());
    }
}
