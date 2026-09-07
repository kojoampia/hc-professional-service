package net.jojoaddison.repository;

import net.jojoaddison.domain.PersonalDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Document entity.
 */
@SuppressWarnings("unused")
@Repository
public interface PersonalDocumentRepository extends MongoRepository<PersonalDocument, String> {
    java.util.List<PersonalDocument> findByProfileId(String profileId);

    /**
     * Live credentials of a type past a date — the compliance sweep and the expiring-license
     * watchlist (backlog.md item 20).
     *
     * <p>{@code SupersededAtIsNull} is not decoration: without it both readers joined <em>every</em>
     * lapsed LICENSE to its application, so a clinician who renewed last year stayed on the watchlist
     * for ever and {@code metrics().expiringLicenses30d} only grew. There is deliberately no
     * unfiltered form of this query left — the two callers are the whole reason it exists, and a
     * reader that wants the archive should go through {@link #findByProfileId}, which still returns
     * everything.
     *
     * <p>Spring Data renders {@code IsNull} as {@code {superseded_at: null}}, and MongoDB's
     * equality-to-null matches a <b>missing</b> field as well as a present null. Every row written
     * before this field existed therefore counts as live, which is what it is — there is no migration
     * framework in this service to backfill them with.
     */
    java.util.List<PersonalDocument> findByTypeAndExpiryDateLessThanAndSupersededAtIsNull(
        net.jojoaddison.domain.enumeration.DocumentType type,
        java.time.LocalDate date
    );
}
