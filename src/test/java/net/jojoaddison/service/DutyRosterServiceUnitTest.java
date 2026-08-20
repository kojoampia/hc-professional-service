package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.service.DutyRosterService.InvalidRoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The window and overlap rules for a round of visits (docs/duty-roster.md § 4, DR2).
 *
 * <p>Almost all of this is about the {@code NIGHT} wrap, which the plan singles out as "the single
 * easiest thing here to get subtly wrong" — a shift that starts at 23:00 and ends at 07:00 the next
 * morning means the same clock time can be legal or illegal depending on the shift, and two visits
 * an hour apart can be a day apart. These are unit tests rather than integration ones because the
 * rules deserve exhaustive, cheap coverage; the endpoint wiring is proved in {@code DutyRosterRoundsIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DutyRosterServiceUnitTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 20);
    private static final String PRO = "professional-1";

    @Mock
    private DutyRosterRepository dutyRosterRepository;

    @Mock
    private PatientServiceClient patientServiceClient;

    private DutyRosterService service;

    @BeforeEach
    void setUp() {
        service = new DutyRosterService(dutyRosterRepository, patientServiceClient);
        when(dutyRosterRepository.findRoundsAround(anyString(), any(), any())).thenReturn(List.of());
        when(patientServiceClient.profiles()).thenReturn(List.of());
    }

    private static Visit visit(String customerId, String start, String end) {
        return new Visit().customerId(customerId).startTime(LocalTime.parse(start)).endTime(LocalTime.parse(end));
    }

    private static DutyRoster round(ShiftType shift, Visit... visits) {
        return new DutyRoster()
            .date(DATE)
            .duty(DutyRole.NURSE)
            .professionalId(PRO)
            .shift(shift)
            .name("Round")
            .visits(new java.util.ArrayList<>(List.of(visits)));
    }

    // ------------------------------------------------------------- resolution

    @Test
    void placesDayAndEveningTimesOnTheAssignmentDate() {
        assertThat(DutyRosterService.resolve(DATE, ShiftType.DAY, LocalTime.of(9, 0))).contains(DATE.atTime(9, 0));
        assertThat(DutyRosterService.resolve(DATE, ShiftType.EVENING, LocalTime.of(18, 0))).contains(DATE.atTime(18, 0));
    }

    @Test
    void placesNightTimesBeforeSevenOnTheFollowingDate() {
        // The wrap. 23:30 is the assignment date; 01:00 is the morning after, and reading it as the
        // assignment date would put the visit 24 hours early with nothing to flag it.
        assertThat(DutyRosterService.resolve(DATE, ShiftType.NIGHT, LocalTime.of(23, 30))).contains(DATE.atTime(23, 30));
        assertThat(DutyRosterService.resolve(DATE, ShiftType.NIGHT, LocalTime.of(1, 0))).contains(DATE.plusDays(1).atTime(1, 0));
    }

    @Test
    void rejectsTimesOutsideTheShiftWindow() {
        assertThat(DutyRosterService.resolve(DATE, ShiftType.DAY, LocalTime.of(6, 59))).isEmpty();
        assertThat(DutyRosterService.resolve(DATE, ShiftType.DAY, LocalTime.of(15, 1))).isEmpty();
        assertThat(DutyRosterService.resolve(DATE, ShiftType.EVENING, LocalTime.of(14, 59))).isEmpty();
        // Mid-afternoon is nobody's night shift, on either date.
        assertThat(DutyRosterService.resolve(DATE, ShiftType.NIGHT, LocalTime.of(15, 0))).isEmpty();
    }

    @Test
    void flexibleAcceptsAnyTimeOnItsDate() {
        assertThat(DutyRosterService.resolve(DATE, ShiftType.FLEXIBLE, LocalTime.MIDNIGHT)).contains(DATE.atStartOfDay());
        assertThat(DutyRosterService.resolve(DATE, ShiftType.FLEXIBLE, LocalTime.of(23, 59))).contains(DATE.atTime(23, 59));
    }

    // -------------------------------------------------------------- validation

    @Test
    void acceptsARoundWithNoVisits() {
        // Ward cover, on call, administrative time. A shift is not required to serve anyone.
        assertThatCode(() -> service.validateRound(round(ShiftType.DAY))).doesNotThrowAnyException();
    }

    @Test
    void acceptsAVisitThatCrossesMidnightWithinANightShift() {
        assertThatCode(() -> service.validateRound(round(ShiftType.NIGHT, visit("c-1", "23:30", "00:30")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAVisitOutsideTheWindowWithAMessageNamingTheShift() {
        assertThatThrownBy(() -> service.validateRound(round(ShiftType.DAY, visit("c-1", "06:00", "08:00"))))
            .isInstanceOf(InvalidRoundException.class)
            .hasMessageContaining("06:00")
            .hasMessageContaining("DAY");
    }

    @Test
    void rejectsANightVisitWrittenBackwards() {
        // Both times resolve legally — 01:00 to the next morning, 23:30 to the assignment date — but
        // to instants a day apart in the wrong order. Comparing the clock times alone would accept it.
        assertThatThrownBy(() -> service.validateRound(round(ShiftType.NIGHT, visit("c-1", "01:00", "23:30"))))
            .isInstanceOf(InvalidRoundException.class)
            .hasMessageContaining("must be after");
    }

    @Test
    void rejectsAZeroLengthVisit() {
        assertThatThrownBy(() -> service.validateRound(round(ShiftType.DAY, visit("c-1", "09:00", "09:00")))).isInstanceOf(
            InvalidRoundException.class
        );
    }

    // ----------------------------------------------------------------- overlap

    @Test
    void allowsBackToBackVisits() {
        // The normal shape of a round: one call ends, the clinician walks next door. Treating the
        // shared boundary as a clash would reject most real rounds.
        assertThatCode(
            () -> service.validateRound(round(ShiftType.DAY, visit("c-1", "09:00", "10:00"), visit("c-2", "10:00", "11:00")))
        ).doesNotThrowAnyException();
    }

    @Test
    void rejectsOverlappingVisitsWithinTheSameRound() {
        assertThatThrownBy(
            () -> service.validateRound(round(ShiftType.DAY, visit("c-1", "09:00", "10:30"), visit("c-2", "10:00", "11:00")))
        )
            .isInstanceOf(InvalidRoundException.class)
            .hasMessageContaining("overlap within the round");
    }

    @Test
    void rejectsOverlapWithAnAlreadyStoredRound() {
        when(dutyRosterRepository.findRoundsAround(anyString(), any(), any())).thenReturn(
            List.of(round(ShiftType.DAY, visit("c-9", "09:00", "12:00")).id("stored"))
        );
        assertThatThrownBy(() -> service.validateRound(round(ShiftType.DAY, visit("c-1", "11:00", "13:00"))))
            .isInstanceOf(InvalidRoundException.class)
            .hasMessageContaining("overlaps an existing assignment");
    }

    @Test
    void rejectsOverlapAcrossMidnightWithTheNightBefore() {
        // The case a same-date-only check silently permits, and the reason the neighbour query spans
        // date-1 to date+1. Yesterday's NIGHT round runs 23:00 into 06:00 *this* morning; a FLEXIBLE
        // round today covers the whole of today, including that early morning. The two rounds carry
        // different dates and collide anyway.
        DutyRoster lastNight = round(ShiftType.NIGHT, visit("c-9", "23:00", "06:00")).date(DATE.minusDays(1)).id("stored");
        when(dutyRosterRepository.findRoundsAround(anyString(), any(), any())).thenReturn(List.of(lastNight));

        assertThatThrownBy(() -> service.validateRound(round(ShiftType.FLEXIBLE, visit("c-1", "05:00", "08:00"))))
            .isInstanceOf(InvalidRoundException.class)
            .hasMessageContaining("overlaps an existing assignment");
    }

    @Test
    void aNightRoundRunsIntoTheFollowingMorningNotTheCurrentOne() {
        // The mirror of the case above, and the one that makes it non-obvious: a NIGHT round dated
        // the 20th covers the 20th 23:00 to the 21st 07:00. It cannot clash with a round that ended
        // on the morning *of* the 20th, however close the clock times look.
        DutyRoster lastNight = round(ShiftType.NIGHT, visit("c-9", "23:00", "06:00")).date(DATE.minusDays(1)).id("stored");
        when(dutyRosterRepository.findRoundsAround(anyString(), any(), any())).thenReturn(List.of(lastNight));

        assertThatCode(() -> service.validateRound(round(ShiftType.NIGHT, visit("c-1", "05:00", "06:30")))).doesNotThrowAnyException();
    }

    @Test
    void doesNotCollideWithItselfWhenReSaved() {
        DutyRoster existing = round(ShiftType.DAY, visit("c-1", "09:00", "10:00")).id("same");
        when(dutyRosterRepository.findRoundsAround(anyString(), any(), any())).thenReturn(List.of(existing));

        assertThatCode(() -> service.validateRound(existing)).doesNotThrowAnyException();
    }

    @Test
    void allowsAnotherProfessionalToVisitTheSameCustomerAtTheSameTime() {
        // Overlap is a constraint on the professional, not the customer: two clinicians may attend
        // one household together, and the repository query is already scoped by professionalId.
        when(dutyRosterRepository.findRoundsAround(anyString(), any(), any())).thenReturn(List.of());

        assertThatCode(
            () -> service.validateRound(round(ShiftType.DAY, visit("shared-customer", "09:00", "10:00")))
        ).doesNotThrowAnyException();
    }
}
