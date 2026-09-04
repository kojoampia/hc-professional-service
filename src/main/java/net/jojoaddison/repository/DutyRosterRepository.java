package net.jojoaddison.repository;

import java.time.LocalDate;
import java.util.List;
import net.jojoaddison.domain.DutyRoster;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for professional duty-roster assignments.
 *
 * <p>Every finder here leads with {@code professionalId} and narrows on {@code date}, which is the
 * order of the {@code professional_date_idx} compound index on {@link DutyRoster}. Keep new finders
 * in that shape.
 *
 * <p><b>The inclusive date ranges are hand-written {@code @Query}s and have to be.</b> Two things
 * rule out the derived-name form, and both were found the hard way:
 *
 * <ul>
 *   <li>{@code Between} is <b>exclusive</b> in the MongoDB module — {@code $gt}/{@code $lt} — unlike
 *       the JPA module, where the same keyword is inclusive. It produces wrong answers rather than
 *       errors: a single-day range returns nothing, and every range silently drops its endpoints.
 *   <li>Spelling it out as {@code ...DateGreaterThanEqualAndDateLessThanEqual...} does not work
 *       either. Spring Data parses the name but the driver rejects it at call time with
 *       {@code InvalidMongoDbApiUsageException: you can't add a second 'date' expression} — a
 *       {@code Document} cannot carry two conditions on one key. It compiles, starts, and 500s.
 * </ul>
 *
 * <p>Field names below are the stored ones ({@code professional_id}, not {@code professionalId}), as
 * {@code @Query} bypasses the property mapping the derived finders use.
 */
@Repository
public interface DutyRosterRepository extends MongoRepository<DutyRoster, String> {
    List<DutyRoster> findByProfessionalIdOrderByDateAscShiftAsc(String professionalId);

    /*
     * There is deliberately no unbounded whole-estate finder. `findAllByOrderByDateAscShiftAsc` was
     * one and is gone: it backed GET /api/duty-roster/all, which returned every assignment on the
     * estate in a single response and grew with the roster rather than with the number of
     * professionals. The estate read is paged now — `findAll(Pageable)` through
     * `DutyRosterService.estateRoster`, which supplies the date/shift sort this finder's name used to
     * carry. Do not add another; a finder that cannot be bounded is one an endpoint will eventually
     * expose unbounded.
     */

    /** Inclusive on both ends — a range read of "from the 1st to the 7th" includes the 7th. */
    @Query(value = "{ 'professional_id': ?0, 'date': { $gte: ?1, $lte: ?2 } }", sort = "{ 'date': 1, 'shift': 1 }")
    List<DutyRoster> findRoundsInRange(String professionalId, LocalDate from, LocalDate to);

    /**
     * Used for overlap checking, which has to look at the neighbouring dates as well as the target
     * one: a {@code NIGHT} round starting on the 3rd runs into the morning of the 4th, so a visit on
     * the 4th can collide with it. Callers pass date−1 to date+1.
     *
     * <p>Inclusive bounds are the whole point. With an exclusive range this returns only the target
     * date, the neighbours it exists to fetch are dropped, and every cross-midnight double-booking is
     * admitted silently — a failure no unit test that stubs this repository can see.
     */
    @Query("{ 'professional_id': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<DutyRoster> findRoundsAround(String professionalId, LocalDate from, LocalDate to);

    /** Open-ended ranges need only one condition, so these stay derived. */
    List<DutyRoster> findByProfessionalIdAndDateGreaterThanEqualOrderByDateAscShiftAsc(String professionalId, LocalDate from);

    List<DutyRoster> findByProfessionalIdAndDateLessThanEqualOrderByDateAscShiftAsc(String professionalId, LocalDate to);

    /** The purge sweep's candidate set: rounds old enough that their snapshots must go. */
    List<DutyRoster> findByDateLessThan(LocalDate date);

    /**
     * Rounds that include a visit to one customer, within a date range — the patient day plan.
     *
     * <p><b>The one finder here that does not lead with {@code professional_id}</b>, and the class
     * note above says to keep new ones in that shape. This one cannot be: the question is "who is
     * visiting <em>me</em>", so the professional is the answer rather than the input. It leads with
     * {@code visits.customer_id} instead, which is why {@link DutyRoster} gained an index on that
     * path in the same change — without one this is a collection scan that grows with the estate's
     * whole history rather than with one patient's.
     *
     * <p>Hand-written and inclusive for the two reasons the class note gives: {@code Between} is
     * exclusive in the MongoDB module, and the two-condition spelling is rejected at call time.
     *
     * <p><b>It is a filter, not an authorization check.</b> Passing a customer id here returns that
     * customer's rounds whoever is asking; {@code CustomerDayPlanService} decides who may ask, and
     * nothing should reach this without going through it.
     */
    @Query(value = "{ 'visits.customer_id': ?0, 'date': { $gte: ?1, $lte: ?2 } }", sort = "{ 'date': 1, 'shift': 1 }")
    List<DutyRoster> findRoundsForCustomer(String customerId, LocalDate from, LocalDate to);
}
