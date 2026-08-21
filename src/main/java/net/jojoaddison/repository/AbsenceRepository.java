package net.jojoaddison.repository;

import java.time.LocalDate;
import java.util.List;
import net.jojoaddison.domain.Absence;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for professional absences.
 *
 * <p>The overlap finder is a hand-written {@code @Query} for the same two reasons as
 * {@link DutyRosterRepository}'s: {@code Between} is exclusive in the MongoDB module, and a derived
 * name cannot carry two conditions on one property — it parses, then fails at call time. Field names
 * are the stored ones, since {@code @Query} bypasses property mapping.
 */
@Repository
public interface AbsenceRepository extends MongoRepository<Absence, String> {
    List<Absence> findByProfessionalIdOrderByFromDateAsc(String professionalId);

    List<Absence> findAllByOrderByFromDateAsc();

    /**
     * Absences for this professional that touch the given range at all.
     *
     * <p>Two ranges overlap when each starts before the other ends — which is why this is
     * {@code from_date <= to} and {@code to_date >= from}, and <b>not</b> the tempting
     * "{@code from_date} within the range". An absence that starts before the window and runs into it
     * is exactly the case that matters, and the tempting form silently misses it.
     */
    @Query(value = "{ 'professional_id': ?0, 'from_date': { $lte: ?2 }, 'to_date': { $gte: ?1 } }", sort = "{ 'from_date': 1 }")
    List<Absence> findOverlapping(String professionalId, LocalDate from, LocalDate to);

    /**
     * The one-sided halves of {@link #findOverlapping}, for a range open at one end.
     *
     * <p><b>A missing bound picks a different finder; it never becomes a sentinel date.</b>
     * Substituting {@code LocalDate.MIN}/{@code MAX} and reusing the two-sided query returns
     * <em>nothing</em> rather than everything — BSON dates stop around year 292278994, so the
     * comparison matches no document. It is the same trap {@link DutyRosterRepository} carries the
     * note about, and it fails as an empty calendar rather than as an error.
     *
     * <p>Derived names are safe here, unlike the two-sided case: each carries a single condition on a
     * single field, so nothing tries to put two expressions on one key.
     */
    List<Absence> findByProfessionalIdAndToDateGreaterThanEqualOrderByFromDateAsc(String professionalId, LocalDate from);

    List<Absence> findByProfessionalIdAndFromDateLessThanEqualOrderByFromDateAsc(String professionalId, LocalDate to);
}
