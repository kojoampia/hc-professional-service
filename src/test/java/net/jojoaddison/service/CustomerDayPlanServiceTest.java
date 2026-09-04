package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The day plan's window and its refusals, without a container.
 *
 * <p>{@code CustomerDayPlanIT} covers what the wire looks like — and, in particular, that the two
 * 403s are byte-for-byte the same. This covers the parts that are cheaper to state here: which dates
 * are read when the caller names none, and that a refusal costs no query.
 */
class CustomerDayPlanServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);
    private static final String ME = "patient-me";

    private final DutyRosterRepository rounds = mock(DutyRosterRepository.class);
    private final ProfileRepository profiles = mock(ProfileRepository.class);
    private final PatientServiceClient patientServiceClient = mock(PatientServiceClient.class);

    private final CustomerDayPlanService service = new CustomerDayPlanService(rounds, profiles, patientServiceClient);

    @BeforeEach
    void signInAsThePatient() {
        Jwt token = new Jwt("t", null, null, Map.of("alg", "none"), Map.of("sub", "a-login", "email", "me@abofonsa.care"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(token, token, List.of()));
        when(patientServiceClient.profileByEmail(anyString())).thenReturn(Optional.empty());
        when(patientServiceClient.profileByEmail("me@abofonsa.care")).thenReturn(
            Optional.of(new PatientProfile("p", ME, "K", null, "M", null, null, null, null, null, null, null))
        );
        when(rounds.findRoundsForCustomer(anyString(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /**
     * With no bounds, the read is the fortnight around today rather than the patient's history.
     *
     * <p>Bounded by construction. This repository has already had to delete one unbounded finder —
     * {@code findAllByOrderByDateAscShiftAsc} — after it backed a read that grew with the estate's
     * whole history, and a day plan asked without dates is the same shape one patient along.
     */
    @Test
    void defaultsToTheFortnightAroundToday() {
        service.forCustomer(ME, null, null, TODAY);

        verify(rounds).findRoundsForCustomer(
            eq(ME),
            eq(TODAY.minusDays(CustomerDayPlanService.DEFAULT_DAYS_BACK)),
            eq(TODAY.plusDays(CustomerDayPlanService.DEFAULT_DAYS_AHEAD))
        );
    }

    /** Explicit bounds are used as given, and are inclusive — the repository query says so. */
    @Test
    void usesTheBoundsTheCallerGives() {
        service.forCustomer(ME, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), TODAY);

        verify(rounds).findRoundsForCustomer(ME, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));
    }

    /**
     * A backwards range is empty, not forbidden.
     *
     * <p>The caller is entitled; they have asked for a window that cannot contain anything. Refusing
     * here would report an authorization failure for a typo, which is the mirror of the mistake this
     * endpoint's 403 exists to avoid.
     */
    @Test
    void answersEmptyForABackwardsRangeRatherThanRefusing() {
        assertThat(service.forCustomer(ME, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 1), TODAY)).isEmpty();
        verify(rounds, never()).findRoundsForCustomer(anyString(), any(), any());
    }

    /** Somebody else's id is refused, and the refusal costs no query. */
    @Test
    void refusesSomebodyElsesCustomerIdWithoutReadingTheRoster() {
        assertThatThrownBy(() -> service.forCustomer("patient-somebody-else", null, null, TODAY)).isInstanceOf(
            CustomerDayPlanService.DayPlanForbiddenException.class
        );

        verify(rounds, never()).findRoundsForCustomer(anyString(), any(), any());
    }

    /**
     * A blank id is the same refusal as every other, with the same message.
     *
     * <p>Every path out of {@code requireCaller} carries one message, so that no difference between
     * "you sent nothing", "you are not that customer" and "nobody is that customer" reaches the
     * caller. The assertion is on the message and not merely on the type.
     */
    @Test
    void refusesABlankCustomerIdWithTheSameMessageAsEverythingElse() {
        assertThatThrownBy(() -> service.forCustomer("  ", null, null, TODAY))
            .isInstanceOf(CustomerDayPlanService.DayPlanForbiddenException.class)
            .hasMessage("Not your day plan");

        assertThatThrownBy(() -> service.forCustomer("patient-somebody-else", null, null, TODAY)).hasMessage("Not your day plan");
    }

    /** No token at all is the same refusal again — there is no anonymous path through this service. */
    @Test
    void refusesWhenThereIsNoCallerAtAll() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.forCustomer(ME, null, null, TODAY))
            .isInstanceOf(CustomerDayPlanService.DayPlanForbiddenException.class)
            .hasMessage("Not your day plan");
    }
}
