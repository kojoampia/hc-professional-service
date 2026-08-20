package net.jojoaddison.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import org.junit.jupiter.api.Test;

/**
 * The customer snapshot must not leave the database (docs/duty-roster.md § 6, DR2).
 *
 * <p>DR2 put personal data into professionalservice for the first time — names, street addresses and
 * phone numbers, on rounds. The 90-day purge governs how long it lives in the collection, but says
 * nothing about the copies that leak out sideways, and those have no retention policy at all: a log
 * line lives as long as the log, and an OpenTelemetry span attribute is shipped to a shared collector
 * on another host.
 *
 * <p>{@code toString()} is the realistic leak and the reason this test exists. It is what
 * {@code log.debug("...: {}", round)} calls, what an exception message interpolates, and what a
 * future span attribute would most likely be built from — and the generated JHipster form of it
 * renders every field, so the leak is what you get by <em>not</em> thinking about it. Both classes
 * therefore print identifiers only, and this holds that line where a reviewer might not.
 *
 * <p>The other two channels are guarded elsewhere: the Kafka envelope carries an entity id and an
 * actor (§ Domain events, "payloads carry identifiers only") and is never handed a round, and no code
 * here sets span attributes by hand.
 */
class DutyRosterVisitPrivacyTest {

    private static final String NAME = "Akosua Mensah";
    private static final String ADDRESS = "GA-123-4567, 5 Ankobra River Street, Osu";
    private static final String PHONE = "0244000111";

    private static Visit snapshotted() {
        return new Visit()
            .customerId("patient-7")
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .customerName(NAME)
            .customerAddress(ADDRESS)
            .customerPhone(PHONE);
    }

    private static DutyRoster roundWithSnapshot() {
        return new DutyRoster()
            .id("round-1")
            .date(LocalDate.of(2026, 8, 20))
            .duty(DutyRole.NURSE)
            .professionalId("professional-1")
            .shift(ShiftType.DAY)
            .name("Ward 3")
            .visits(List.of(snapshotted()));
    }

    @Test
    void visitToStringCarriesNoPersonalData() {
        String rendered = snapshotted().toString();

        assertThat(rendered).contains("patient-7", "09:00").doesNotContain(NAME, ADDRESS, PHONE);
    }

    @Test
    void dutyRosterToStringCarriesNoPersonalData() {
        String rendered = roundWithSnapshot().toString();

        // The count is useful and safe; the contents are neither.
        assertThat(rendered).contains("round-1", "visits=1").doesNotContain(NAME, ADDRESS, PHONE, "patient-7");
    }

    @Test
    void clearingASnapshotKeepsTheCustomerIdAndReportsWhetherItDidAnything() {
        Visit visit = snapshotted();

        assertThat(visit.clearSnapshot()).isTrue();
        assertThat(visit.getCustomerId()).isEqualTo("patient-7");
        assertThat(visit.getCustomerName()).isNull();
        assertThat(visit.getCustomerAddress()).isNull();
        assertThat(visit.getCustomerPhone()).isNull();

        // False the second time, which is what lets the purge skip rewriting an already-clean round
        // and report an honest zero.
        assertThat(visit.clearSnapshot()).isFalse();
    }

    @Test
    void aRoundNormalisesANullVisitListToEmpty() {
        // Callers iterate getVisits() without null-checking, including the purge and the validator.
        assertThat(new DutyRoster().visits(null).getVisits()).isEmpty();
    }
}
