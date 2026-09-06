package net.jojoaddison;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.net.URI;

@AnalyzeClasses(packagesOf = ProfessionalServiceApp.class, importOptions = DoNotIncludeTests.class)
class TechnicalStructureTest {

    // prettier-ignore
    @ArchTest
    static final ArchRule respectsTechnicalArchitectureLayers = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Config").definedBy("..config..")
        .layer("Web").definedBy("..web..")
        .optionalLayer("Service").definedBy("..service..")
        .layer("Security").definedBy("..security..")
        .optionalLayer("Persistence").definedBy("..repository..")
        .layer("Domain").definedBy("..domain..")

        .whereLayer("Config").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("Config")
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Web", "Config")
        .whereLayer("Security").mayOnlyBeAccessedByLayers("Config", "Service", "Web")
        .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Service", "Security", "Web", "Config")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Persistence", "Service", "Security", "Web", "Config")

        .ignoreDependency(belongToAnyOf(ProfessionalServiceApp.class), alwaysTrue())
        .ignoreDependency(alwaysTrue(), belongToAnyOf(
            net.jojoaddison.config.Constants.class,
            net.jojoaddison.config.ApplicationProperties.class
        ));

    /**
     * A {@code Location} must be built from the request, not from a string (backlog.md item 41).
     *
     * <p>Eleven resources hand-built {@code new URI("/api/things/" + id)}. That is an absolute-path
     * reference, so a client behind the gateway resolved it against the origin and lost the
     * {@code /services/professionalservice} prefix the gateway had stripped — a 404 when followed.
     * {@link net.jojoaddison.web.rest.util.LocationUri#of(String)} goes through
     * {@code ServletUriComponentsBuilder} instead, which reads the forwarded headers
     * {@link net.jojoaddison.config.WebConfigurer#forwardedHeaderFilter()} put on the request.
     *
     * <p><b>This rule exists because the same defect has now been fixed twice.</b> Item 31 fixed it
     * for the {@code Link} header on the paginated reads and its sweep stopped one response class
     * short; item 41 is the second half. Both were families of call sites that each looked
     * individually reasonable, and nothing failed until someone followed the URL. A twelfth resource
     * written next month would repeat it for a third time — and {@code LocationHeaderIT} could not
     * catch that one, because a list of endpoints in a test only covers endpoints someone remembered
     * to add. This rule needs no such list.
     *
     * <p><b>Broad within {@code ..web.rest..}, and confined to it.</b> Those are two separate
     * decisions and it is worth not conflating them, because an earlier draft of this comment did.
     * Constructing a {@link URI} is entirely fine elsewhere in the service — an outbound call to a
     * sibling stack builds one — so the rule stops at the package boundary rather than becoming an
     * estate-wide ban that people would learn to route around. <i>Inside</i> that boundary it is
     * deliberately total, for the reason below.
     *
     * <p><b>It bans the whole type here, not just {@code new URI(String)}, and that breadth was
     * earned.</b> The first version of this rule named that one constructor, and the review of item
     * 41 broke it in a minute: {@code URI.create("/api/absences/" + id)} produces a byte-identical
     * defect and passes, because the constructor call it makes belongs to {@code java.net.URI}
     * rather than to {@code ..web.rest..}. {@code new URI(scheme, host, path, fragment)} and
     * {@code UriComponentsBuilder.fromPath(...)} were two more ways through.
     *
     * <p>What makes that more than a curiosity is that <b>this change steers an author toward the
     * bypass</b>: it deleted {@code throws URISyntaxException} from eleven signatures, and that
     * checked exception was the only friction discouraging {@code new URI}. {@code URI.create} is
     * the standard way to avoid it and is what an IDE quick-fix offers. A rule that stops only the
     * spelling nobody will use next is the vacuous-rule failure mode wearing a green tick — which is
     * precisely the shape of defect this whole item exists to close.
     *
     * <p>So the rule is now "{@code web.rest} does not touch {@code java.net.URI} directly; it goes
     * through {@link net.jojoaddison.web.rest.util.LocationUri}". {@code LocationUri} itself passes:
     * it calls {@code UriComponents.toUri()} and merely returns the result, never invoking a member
     * of {@code URI}.
     *
     * <p><b>{@code ..web.rest.errors..} is excluded, and the distinction is the point of the rule
     * rather than an exemption from it.</b> {@code ErrorConstants} and {@code ExceptionTranslator}
     * build six URIs with {@code URI.create}, and every one is an RFC 7807 <i>problem type</i> —
     * {@code https://www.jhipster.tech/problem/problem-with-message}. A problem type is a stable
     * identifier for a <i>class of error</i>, deliberately not dereferenced and deliberately not
     * this host's. Nothing about the request could make it more correct, which is exactly why it is
     * the one place a constant URI is right. The rule covers URIs that name a resource here; those
     * do not.
     */
    @ArchTest
    static final ArchRule locationHeadersAreBuiltFromTheRequest = noClasses()
        .that()
        .resideInAPackage("..web.rest..")
        .and()
        .resideOutsideOfPackage("..web.rest.errors..")
        .should()
        .callCodeUnitWhere(target(owner(type(URI.class))))
        .because("a hand-built URI drops the gateway prefix, scheme and host — use LocationUri.of(id), which reads the forwarded headers");
}
