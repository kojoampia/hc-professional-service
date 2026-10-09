package net.jojoaddison.config;

import java.util.List;
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
 * Drops {@code area_code} and {@code state} wherever an {@link net.jojoaddison.domain.Address} is
 * stored (F10) — {@code profile.md}'s Address model has eight fields and names neither.
 *
 * <h2>Three places, because an Address is stored three ways</h2>
 *
 * <ul>
 *   <li>{@code address} — the collection {@code Address} is a {@code @Document} of.</li>
 *   <li>{@code profile.address} — embedded, one per clinician.</li>
 *   <li>{@code profile.contacts[].address} — embedded, <b>one per next of kin</b>, which is the
 *       reason this removal was worth doing rather than tolerating: since the contacts became a list
 *       the two dead fields were multiplied by its length.</li>
 * </ul>
 *
 * <p>⚠ <b>The third needs {@code contacts.$[].address} and not {@code contacts.address}</b> — an
 * all-positional operator, because the path crosses an array and a plain dotted path unsets nothing
 * on one. That is the clause a reader is most likely to get wrong, and it fails <em>silently</em>:
 * the update reports a modified count and the keys stay where they are.
 *
 * <h2>⭐ It discarded nothing, measured rather than assumed</h2>
 *
 * <p>On the quality stack before the deletion: <b>0</b> documents carried a value in either field —
 * {@code profile.address} 0, {@code profile.contacts[].address} 0, and the {@code address}
 * collection was empty. The two were <em>input</em> fields with no input anywhere in the estate: no
 * form filled them, no migration set them, and nothing but a hand-written request body could ever
 * have. This runs for production and for a database restored from an older backup.
 *
 * <h2>It REMOVES rather than remaps, which is the deliberate opposite of its two neighbours</h2>
 *
 * <p>{@code ShiftTypeMigration} rewrites a retired {@code MORNING} to {@code DAY} because a shift
 * that was worked is a fact wanting a new name, and {@code EmergencyContactListMigration} moves a
 * line-of-text address into {@code street_address} for the same reason.
 * {@code AngelDutyRoleMigration} deletes instead, because mapping an angel's shift to {@code CARER}
 * invents a cover arrangement that never existed. <b>This is the third kind and simplest: there is
 * no fact to carry.</b> A value in these fields would have no field to go to — none of the eight
 * remaining means "area code" or "state" — so the only honest options are "leave it unreadable" and
 * "drop it", and the owner chose the deletion knowing the count above.
 *
 * <p>⚠ <b>Unlike {@code ProfileEnumValueMigration} this writes no quarantine record</b>, and the
 * difference is the measurement, not a different standard: there is nothing to record. If this ever
 * runs somewhere the count is non-zero, the log line below names how many rows it touched per
 * location — which is the one thing worth having and is why it counts rather than firing three blind
 * updates.
 *
 * <h2>Idempotent, by construction</h2>
 *
 * <p>It matches only documents that still have one of the keys and unsets them, so a second run
 * matches nothing. No marker document, no flag to be wrong. Excluded from {@code testdev} and
 * {@code testprod} like its neighbours; {@code ShiftTypeMigration} carries the reasoning for that
 * expression. Spring Boot does not invoke {@link ApplicationRunner} beans under
 * {@code @SpringBootTest}, so {@code AddressFieldRemovalMigrationIT} constructs this class and calls
 * {@link #run} itself.
 */
@Component
@Profile("!testdev & !testprod")
public class AddressFieldRemovalMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AddressFieldRemovalMigration.class);

    /** The two retired keys, in {@code @Field} spelling. */
    static final List<String> RETIRED_KEYS = List.of("area_code", "state");

    private final MongoTemplate mongoTemplate;

    public AddressFieldRemovalMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        long dropped = 0;
        dropped += unset("address", "", "");
        dropped += unset("profile", "address.", "address.");
        // THE QUERY AND THE UPDATE PATHS DIFFER HERE AND ONLY HERE, and that asymmetry is the whole
        // of the array case. A QUERY traverses an array implicitly, so `contacts.address.area_code`
        // matches a document any of whose contacts carries the key — adding `$[]` there would make
        // the path invalid. An UPDATE does not traverse: `contacts.address.area_code` unsets nothing
        // and reports a modified document while doing it, which is why `$[]` is required on that
        // side. One prefix for both is the mistake this signature exists to prevent.
        dropped += unset("profile", "contacts.address.", "contacts.$[].address.");
        if (dropped == 0) {
            log.debug("No stored {} found — nothing to remove", RETIRED_KEYS);
        }
    }

    /**
     * Unsets both retired keys under an embedded address, and says how many documents carried one.
     *
     * @param collection the collection to sweep.
     * @param queryPrefix the path to the address as a <em>query</em> names it, with its trailing dot,
     *     or {@code ""} for the {@code address} collection itself.
     * @param updatePrefix the same path as an <em>update</em> names it — identical unless the path
     *     crosses an array, in which case it needs {@code $[]}. See the call sites.
     */
    private long unset(String collection, String queryPrefix, String updatePrefix) {
        Criteria carriesEither = new Criteria()
            .orOperator(RETIRED_KEYS.stream().map(key -> Criteria.where(queryPrefix + key).exists(true)).toArray(Criteria[]::new));
        long carrying = mongoTemplate.count(new Query(carriesEither), collection);
        if (carrying == 0) {
            return 0;
        }
        Update update = new Update();
        RETIRED_KEYS.forEach(key -> update.unset(updatePrefix + key));
        mongoTemplate.updateMulti(new Query(carriesEither), update, collection);
        log.info(
            "Removed {} from {} document(s) at {}.{}",
            RETIRED_KEYS,
            carrying,
            collection,
            queryPrefix.isEmpty() ? "<root>" : queryPrefix
        );
        return carrying;
    }
}
