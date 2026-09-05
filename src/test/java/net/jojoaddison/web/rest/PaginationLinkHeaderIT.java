package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The {@code Link} header must name a URL the caller can actually follow (backlog.md item 31).
 *
 * <p><b>The defect.</b> The gateway routes {@code /services/professionalservice/**} with
 * {@code StripPrefix=2}, so this service sees {@code /api/patients} and — building the header from
 * the request as it sees it — advertised {@code <http://professional.abofonsa.local/api/patients?page=1&size=20>;
 * rel="next"}. Followed as issued that is a 404. It was not one resource's mistake: every paginated
 * read here made it, because they all call {@code ServletUriComponentsBuilder.fromCurrentRequest()}.
 *
 * <p><b>Why no existing test saw it.</b> {@code PatientResourceIT} and {@code DutyRosterVisitPrivacyIT}
 * both assert {@code header().exists("Link")} and stop there. A header that exists and is wrong looks
 * exactly like a header that exists and is right, and no test in this repo had ever read the value.
 * That is the reusable half of this entry, and it is why every assertion below is on the URL rather
 * than on the header's presence.
 *
 * <p><b>Both directions are asserted deliberately.</b> Behind the gateway the prefix must come back;
 * on a direct call — how these very tests reach the service, and how anything inside the compose
 * network reaches it — there is no prefix to put back and the bare path is the correct answer. A fix
 * that hardcoded {@code /services/professionalservice} would pass the first half and break the second.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PaginationLinkHeaderIT {

    private static final String ADMIN = "link-header-admin";

    /** What the gateway strips, and therefore what it reports in {@code X-Forwarded-Prefix}. */
    private static final String GATEWAY_PREFIX = "/services/professionalservice";

    private static final String EXTERNAL_HOST = "professional.abofonsa.com";

    /** Every URL inside a {@code Link} header, in the order the header lists them. */
    private static final Pattern LINK_URL = Pattern.compile("<([^>]+)>");

    @Autowired
    private MockMvc restMockMvc;

    /**
     * The paginated collection reads, as of item 31.
     *
     * <p>This is the grep for {@code PaginationUtil.generatePaginationHttpHeaders} minus
     * {@code /api/patients/&#123;id&#125;/cases}, which cannot answer 200 without a patient in the
     * caller's caseload and there is no patientservice here to give it one. The list is a list and
     * will go stale; the fix it guards is a servlet filter registered for every path, so a sixth
     * paginated read is protected on the day it is written whether or not anyone adds it here.
     */
    static final List<String> PAGINATED_READS = List.of("/api/patients", "/api/profiles", "/api/cases", "/api/duty-roster/all");

    /** A request carrying exactly what Spring Cloud Gateway and the edge nginx put on the wire. */
    private MockHttpServletRequestBuilder asRelayedByTheGateway(String path) {
        return get(path)
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", EXTERNAL_HOST)
            .header("X-Forwarded-Prefix", GATEWAY_PREFIX);
    }

    private List<String> linkUrlsOf(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.LINK);
        assertThat(header).as("a paginated read must emit a Link header").isNotBlank();
        Matcher matcher = LINK_URL.matcher(header);
        return matcher.results().map(match -> match.group(1)).toList();
    }

    /**
     * The header a client can follow, on every paginated read at once.
     *
     * <p>Run against the unfixed service each of these fails on the first URL with
     * {@code https://professional.abofonsa.com/api/patients?page=0&size=20} — right host, right
     * scheme, no prefix.
     */
    @ParameterizedTest
    @FieldSource("PAGINATED_READS")
    @WithMockUser(username = ADMIN, authorities = { "ROLE_ADMIN" })
    void behindTheGatewayEveryLinkCarriesTheStrippedPrefix(String path) throws Exception {
        MvcResult result = restMockMvc.perform(asRelayedByTheGateway(path)).andExpect(status().isOk()).andReturn();

        assertThat(linkUrlsOf(result))
            .isNotEmpty()
            .allSatisfy(url -> assertThat(url).startsWith("https://" + EXTERNAL_HOST + GATEWAY_PREFIX + path + "?"));
    }

    /**
     * A direct call is unchanged, which is the half a hardcoded prefix would have broken.
     *
     * <p>Nothing in the compose network goes through the gateway to reach a sibling container, and
     * these tests do not either. With no {@code X-Forwarded-*} on the request the filter is a no-op
     * and {@code /api/patients} is genuinely the path the caller used.
     */
    @Test
    @WithMockUser(username = ADMIN, authorities = { "ROLE_ADMIN" })
    void aDirectCallStillAdvertisesTheBarePath() throws Exception {
        MvcResult result = restMockMvc.perform(get("/api/patients?page=0&size=20")).andExpect(status().isOk()).andReturn();

        assertThat(linkUrlsOf(result)).isNotEmpty().allSatisfy(url -> assertThat(url).startsWith("http://localhost/api/patients?"));
    }

    /**
     * The scheme half, which only production can be wrong about.
     *
     * <p>TLS terminates at nginx on {@code professional.abofonsa.com}, so the gateway and this service
     * both speak plain HTTP internally. Before item 31 every absolute URL this service produced said
     * {@code http://} for an origin that is HTTPS-only — invisible on quality, where the edge really
     * is HTTP, and therefore exactly the kind of thing that ships.
     */
    @Test
    @WithMockUser(username = ADMIN, authorities = { "ROLE_ADMIN" })
    void theSchemeIsTheEdgesRatherThanTheHop() throws Exception {
        MvcResult result = restMockMvc
            .perform(get("/api/profiles").header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", EXTERNAL_HOST))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(linkUrlsOf(result))
            .isNotEmpty()
            .allSatisfy(url -> assertThat(url).startsWith("https://" + EXTERNAL_HOST + "/api/profiles?"));
    }

    /**
     * Rewriting the request URI must not move the endpoint.
     *
     * <p>{@code ForwardedHeaderFilter} makes {@code getRequestURI()} return the prefixed path and
     * {@code getContextPath()} return the prefix, so the path within the application is unchanged and
     * both handler mapping and the {@code /api/**} rules in
     * {@link net.jojoaddison.config.SecurityConfiguration} keep matching. That is the whole reason the
     * filter is safe to register globally, and it is worth one assertion rather than an argument: a
     * prefixed request reaching a resource at all is what the 200s above already prove, and this pins
     * the security half — an unauthenticated prefixed call is still refused, not routed past the chain.
     */
    @Test
    void aPrefixedRequestIsStillSubjectToTheSecurityChain() throws Exception {
        restMockMvc.perform(asRelayedByTheGateway("/api/patients")).andExpect(status().isUnauthorized());
    }
}
