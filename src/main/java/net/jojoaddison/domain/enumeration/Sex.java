package net.jojoaddison.domain.enumeration;

/**
 * The Sex enumeration — {@code Profile.sex}.
 *
 * <p><b>Two members, and they are {@code profile.md}'s</b>: that document's Profile model types
 * {@code sex} an {@code enum} and names the web file it specifies for it —
 * {@code sex.enum.ts - &#123;'FEMALE','MALE'&#125;}. The field was a free-text {@code String} on both
 * sides until this change, so {@code &#123;"sex":"banana"&#125;} stored and answered 200, and the
 * completeness predicate counted it as provided because it only looked for text.
 *
 * <p><b>Declaration order is {@code FEMALE, MALE} and it is load-bearing.</b>
 * {@code JhipsterEnumFieldValuesTest} compares this list against {@code .jhipster/Profile.json}'s
 * {@code fieldValues} <em>in order</em>, deliberately — a set comparison would pass on an input a
 * regeneration would then emit shuffled.
 *
 * <p>⚠ <b>A stored value that is not one of these is quarantined, not mapped.</b>
 * {@code ProfileEnumValueMigration} is where that happens and why; the quality database held one
 * {@code sex: "female"} when this landed.
 */
public enum Sex {
    FEMALE,
    MALE,
}
