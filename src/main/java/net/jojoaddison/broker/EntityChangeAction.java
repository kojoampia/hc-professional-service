package net.jojoaddison.broker;

/**
 * What happened to one document, on {@code professional.event}.
 *
 * <p><b>Carried in the payload rather than in the event type</b>, which is this repository's own
 * established answer: {@link DomainEventPublisher#publishOnboardingState} says it outright — "state
 * is carried in the payload rather than the event type so a consumer switches on one field instead
 * of matching a family of event names". A channel carrying every entity CRUD event is the strongest
 * possible case for that rule, because the alternative is three type literals multiplied by every
 * collection this service will ever hold.
 *
 * <p>So {@link ProfessionalEventType#ENTITY_CHANGED} is the only {@code type} on this channel, and a
 * consumer reads {@code data.action} to learn which of these three it is.
 *
 * <h2>{@link #CREATED} and {@link #UPDATED} are told apart at conversion, and imperfectly</h2>
 *
 * <p>Spring Data raises the same {@code AfterSaveEvent} for an insert and for a replacement — the
 * event carries no flag, because {@code MongoTemplate.save} is an upsert and does not itself know
 * until it has looked. {@code EntityChangeAnnouncer} therefore decides on the only signal available
 * before the write: whether the entity's id was null when it reached {@code onBeforeConvert}.
 *
 * <p><b>The limit that follows is real and is not worked around:</b> a document saved with an id the
 * caller assigned — a seeded row, a migration writing a known key, an import — reaches conversion
 * already carrying one and is reported {@link #UPDATED}. Nothing in the save path distinguishes that
 * from replacing a row that was already there, so reporting it as a creation would be a guess. An
 * audit trail that says "updated" about a first write is wrong in a way a reader can still reconcile
 * against {@code createdDate}; one that says "created" about a replacement is not.
 */
public enum EntityChangeAction {
    /** The document did not exist: it reached conversion with no id, and Mongo assigned one. */
    CREATED,

    /**
     * The document was written over an id that already existed — or over one the caller supplied.
     * See the class comment: this is the value a first write with a client-assigned id also carries.
     */
    UPDATED,

    /** The document was removed. Reported from the query that matched it; see the announcer. */
    DELETED,
}
