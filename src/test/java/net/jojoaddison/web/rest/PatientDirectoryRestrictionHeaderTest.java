package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.PatientDirectoryService.RestrictedPart;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * That a refused part of the directory reaches the client, and that nothing is paid for it when
 * nothing was refused (backlog item 107).
 *
 * <p><b>A unit test rather than a case in {@code PatientResourceIT}, deliberately.</b> The integration
 * suite needs a Docker daemon and Testcontainers, and on a loaded workstation it is the first thing to
 * time out (backlog item 28) — a guard on a one-line header that only runs when the machine is quiet is
 * a guard that is absent exactly when somebody is in a hurry. Everything this asserts is decided in
 * {@code PatientResource.list} and nothing in it needs a database, a broker or a sibling service.
 */
@ExtendWith(MockitoExtension.class)
class PatientDirectoryRestrictionHeaderTest {

    @Mock
    private PatientDirectoryService patientDirectoryService;

    private PatientResource resource;

    @BeforeEach
    void setUp() {
        // PaginationUtil builds the Link header from the current request, so there has to be one.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/patients")));
        resource = new PatientResource(patientDirectoryService);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void answers(PatientDirectoryService.Directory directory) {
        when(patientDirectoryService.directory(any(), any())).thenReturn(directory);
    }

    private static PatientDirectoryService.Directory directory(java.util.Set<RestrictedPart> restrictions) {
        PatientListItem row = new PatientListItem("p-1", "Ama Mensah", null, "female", false);
        return new PatientDirectoryService.Directory(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1), restrictions);
    }

    /**
     * The ordinary case — five of the eight disciplines — and the one that should pay nothing. An
     * always-present header would make the value meaningless: a client cannot distinguish "nothing was
     * restricted" from "this field means nothing" if it is there every time.
     */
    @Test
    void nothingRestrictedMeansNoHeaderAtAll() {
        answers(directory(java.util.Set.of()));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().headerNames()).doesNotContain(PatientResource.RESTRICTED_PARTS);
        assertThat(response.getBody()).extracting(PatientListItem::id).containsExactly("p-1");
    }

    /** A pharmacist or a chemist: the rows are all there and the blank recency column is explained. */
    @Test
    void aRestrictedPartIsNAMEDonTheResponse() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY)));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("lastActivity");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * Two at once, in declaration order.
     *
     * <p>The order is asserted because it is the difference between a header a client can compare
     * between requests and one that reshuffles for no reason — the reason
     * {@code PatientDirectoryService} collects these in an {@code EnumSet} rather than a {@code HashSet}.
     */
    @Test
    void severalRestrictedPartsAreListedInAStableOrder() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY, RestrictedPart.CASE_ASSIGNMENTS)));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("caseAssignments,lastActivity");
    }

    /**
     * The wire name is the {@code token()}, not the enum constant. A client reads this string, so
     * renaming the constant must not rename the contract — and {@code toString()} would have done
     * exactly that, silently and at the moment somebody tidied an enum.
     */
    @Test
    void theWireNameIsTheTokenAndNotTheEnumConstant() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.CASE_ASSIGNMENTS)));

        assertThat(resource.list(PageRequest.of(0, 20), null, null, null).getHeaders().getFirst(PatientResource.RESTRICTED_PARTS))
            .isEqualTo("caseAssignments")
            .isNotEqualTo(RestrictedPart.CASE_ASSIGNMENTS.name());
    }
}
