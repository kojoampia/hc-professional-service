package net.jojoaddison.config;

import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Migrates the singular {@code emergency_contact} on {@code profile} into the
 * {@code contacts} list, and its line-of-text address into a structured one (profile.md, T1).
 *
 * <pre>
 *   { emergency_contact: { name, relationship, phone, email, address: "12 Oxford St" } }
 *      ->
 *   { contacts: [ { name, relationship, phone, email, address: { street_address: "12 Oxford St" } } ] }
 * </pre>
 *
 * <h2>It REMAPS rather than deletes, and that is {@code ShiftTypeMigration}'s side of the argument</h2>
 *
 * <p>The two migrations beside this one answer the same question in opposite directions, and the
 * distinction is the one to apply here. {@code AngelDutyRoleMigration} <b>deletes</b> the
 * {@code duty_roster} rows that named {@code ROLE_ANGEL}, because mapping an angel's shift onto
 * {@code CARER} or {@code OTHER} would invent a cover arrangement that never existed.
 * {@code ShiftTypeMigration} <b>rewrites</b> a retired {@code MORNING} to {@code DAY}, because a
 * shift that was actually worked is a fact wanting a new name.
 *
 * <p><b>A next of kin is the second kind.</b> Somebody named a person to contact in an emergency;
 * that is a fact, it is still true, and the only thing this change alters is the shape it is written
 * in. Dropping it would silently un-complete the {@code nextOfKin} requirement for every clinician
 * who had satisfied it — and the consequence is not a meter: the {@code ACTIVE} gate refuses
 * activation unless every requirement is satisfied, so a deletion here would quietly block
 * activations, and the subject would be the one asked to re-enter data they had already given.
 *
 * <h2>The address, which is the part with a judgement in it</h2>
 *
 * <p>{@code EmergencyContact.address} was a {@code String} and {@code profile.md} types it
 * {@link net.jojoaddison.domain.Address}. The old line of text lands in {@code street_address},
 * which is the field the wizard's street line writes and the one
 * {@code OnboardingService.addressComplete} reads — so a one-line address stays legible in the one
 * place a reader would look for it. The alternative, guessing which of nine fields each comma-separated
 * fragment belonged to, would manufacture structure nobody entered.
 *
 * <p><b>A blank or absent line leaves no {@code Address} at all</b>, rather than one with nine nulls:
 * "nobody gave an address" and "an address with every field empty" should not read the same, and only
 * the first is true of these rows.
 *
 * <p><b>No {@code _id} is minted for the embedded address.</b> {@code Address} is a
 * {@code @Document} with an {@code @Id}, so an embedded one has somewhere to put a key — but nothing
 * reads it, no collection stores these separately, and inventing identifiers is how a field nobody
 * set starts looking like a field somebody meant. {@code Profile.address} already stores null there.
 *
 * <h2>Idempotent, and by the same construction as its neighbours</h2>
 *
 * <p>It matches on documents that <em>have</em> {@code emergency_contact} and unsets the key as part
 * of the same update, so a second run matches nothing. There is no marker document to keep in step
 * and no "has this run" flag that could be wrong; deployments roll forward and restart freely.
 *
 * <p>⚠ <b>It also matches only documents that do NOT already have {@code contacts}</b>, so a row
 * written by the new code before this ever ran cannot be overwritten by its own legacy key. That
 * combination can genuinely occur: {@code Profile.getEmergencyContact()} is a wire alias that writes
 * into {@code contacts}, so a client sending the old name through the new endpoint produces a
 * {@code contacts} row and no {@code emergency_contact} key — but a database restored from a backup
 * taken mid-migration could hold both, and the newer field has to win.
 *
 * <p>Raw {@link Document}s through {@link MongoTemplate} rather than mapped {@code Profile}s, and
 * necessarily so: the stored {@code address} is a {@code String} where the mapped type is now an
 * {@code Address}, so reading these rows through the entity is exactly what fails until they are
 * fixed. This is the same reason {@code ShiftTypeMigrationIT} writes raw strings — a migration
 * cannot be expressed in the vocabulary it exists to leave behind.
 *
 * <p>There is no migration framework in this repo — no Liquibase, no Mongock — so this runs as an
 * {@link ApplicationRunner}, in the shape of {@link ShiftTypeMigration} beside it.
 *
 * <p><b>Excluded from {@code testdev} and {@code testprod}</b> — the two profiles {@code pom.xml}
 * activates for an integration-test run. The expression is {@code ShiftTypeMigration}'s and the
 * reasoning is recorded there at length, including why it is not {@code !test}: the Spring profile
 * {@code test} is never active under {@code ./mvnw verify}, so {@code !test} excludes nothing from a
 * build while it does exclude a {@code dev,test} deployment — which cost hc-admin an outage.
 *
 * <p>Spring Boot does not invoke {@link ApplicationRunner} beans under {@code @SpringBootTest} — it
 * is {@code SpringApplication.run} that calls them — so {@code EmergencyContactListMigrationIT}
 * constructs this class directly and calls {@link #run(ApplicationArguments)} itself, and
 * {@code EmergencyContactListMigrationProfileTest} pins the registration.
 */
@Component
@Profile("!testdev & !testprod")
public class EmergencyContactListMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmergencyContactListMigration.class);

    private static final String COLLECTION = "profile";

    /** The retired singular key. {@code EmergencyContact} has no {@code @Field}s, so this one does. */
    private static final String LEGACY_FIELD = "emergency_contact";

    /** The list that replaces it. */
    private static final String CONTACTS_FIELD = "contacts";

    /** The contact's own members, mapped by property name because the value type declares no {@code @Field}. */
    private static final String ADDRESS_MEMBER = "address";

    /** {@code Address.streetAddress}'s stored name — that class does declare {@code @Field}s. */
    private static final String STREET_ADDRESS = "street_address";

    private final MongoTemplate mongoTemplate;

    public EmergencyContactListMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query pending = new Query(Criteria.where(LEGACY_FIELD).exists(true).and(CONTACTS_FIELD).exists(false));
        List<Document> rows = mongoTemplate.find(pending, Document.class, COLLECTION);
        if (rows.isEmpty()) {
            log.debug("No singular {} found on {} — nothing to migrate", LEGACY_FIELD, COLLECTION);
            return;
        }

        long migrated = 0;
        for (Document row : rows) {
            Object legacy = row.get(LEGACY_FIELD);
            Update update = new Update().unset(LEGACY_FIELD);
            if (legacy instanceof Document contact) {
                update.set(CONTACTS_FIELD, List.of(withStructuredAddress(contact)));
            }
            // A row whose emergency_contact is not a sub-document holds nothing a contact could be
            // built from, so the key is dropped without a contacts entry rather than wrapping
            // whatever is there in a list. Never seen; stated so the `instanceof` does not read as
            // defensive clutter.
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(row.get("_id"))), update, COLLECTION);
            migrated++;
        }
        log.info("Migrated {} profile(s) from a singular {} to a {} list", migrated, LEGACY_FIELD, CONTACTS_FIELD);
    }

    /** The contact with its line-of-text address lifted into {@code address.street_address}. */
    private static Document withStructuredAddress(Document contact) {
        Document migrated = new Document(contact);
        Object address = migrated.get(ADDRESS_MEMBER);
        if (address instanceof String line && !line.isBlank()) {
            migrated.put(ADDRESS_MEMBER, new Document(STREET_ADDRESS, line));
        } else if (address instanceof String) {
            // Blank, so there is no address rather than an empty one. See the class note.
            migrated.remove(ADDRESS_MEMBER);
        }
        // Anything else is already a sub-document (a row this migration has effectively seen before,
        // or one written by a newer client through the wire alias) and is carried through untouched.
        return migrated;
    }
}
