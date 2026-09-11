package net.jojoaddison.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The JHipster entity definitions in {@code .jhipster/} must describe every field of the document they
 * name — or say, in the file itself, that this format cannot describe it (backlog.md item 21).
 *
 * <p>{@code JhipsterEnumFieldValuesTest} holds the same files to their enums' <em>values</em>; this one
 * holds them to their documents' <em>fields</em>. They are siblings rather than one test because they
 * are different claims about the same input, and item 8 closed the first while opening the second:
 * {@code .jhipster/DutyRoster.json} had no {@code visits} for the whole of DR2, and
 * {@code .jhipster/Profile.json} had none of MOB9's three push preferences, so a regeneration would
 * have emitted documents missing them with nothing failing to build.
 *
 * <h2>The three ways a field may legitimately differ, and all three are derived</h2>
 *
 * <p><b>The id.</b> JHipster never lists the key in {@code fields}, and this repo's inputs disagree
 * with each other about it — {@code Team.json} declares one and the other nine do not. It is skipped on
 * both sides rather than required on either.
 *
 * <p><b>The content type beside a blob.</b> {@code PersonalDocument.dataContentType} exists in the
 * document and in no generator input, and that is correct: the generator derives it. The rule is
 * {@code prepare-field.js}, {@code fieldWithContentType = (fieldType === BYTES || fieldType ===
 * BYTE_BUFFER) && fieldTypeBlobContent !== TEXT} then {@code contentTypeFieldName =
 * `${fieldName}ContentType`}, and it is reproduced below rather than exempted by name — item 21 warned
 * that a naive comparison would call this file wrong when it is the comparison that is wrong.
 *
 * <p><b>A field the format has no way to state.</b> JDL's field vocabulary is scalar
 * ({@code jdl/jhipster/field-types.js}, {@code CommonDBTypes}): no collections, no embedded value
 * types. {@code DutyRoster.visits} is a {@code List<Visit>} over a type with no {@code @Document} and no
 * repository, and {@code Profile.address}, {@code Profile.emergencyContact}, {@code Profile.teamIds} and
 * {@code Team.members} are stored as {@code String} approximations of things that are not strings. Each
 * is declared in its own definition file under {@code fieldsThisFormatCannotExpress}, with the reason.
 *
 * <p><b>The declaration lives in the generator input, not in this test, and that is the point.</b> A
 * list of exemptions here would be read by nobody and would rot quietly; the same list in the file is
 * read by the one person whose action it exists to warn — somebody about to regenerate the entity. So
 * there is one list, it sits where it is needed, and {@link #everyInexpressibleFieldNamedIsRealAndGivesAReason}
 * makes this test enforce that it is accurate rather than merely present: an entry naming a field the
 * document no longer has fails, so the note cannot outlive its subject.
 *
 * <p>{@code fieldsThisFormatCannotExpress} is not a generator option and the generator ignores it —
 * {@code JSONEntity} copies the keys it knows and {@code EntityValidator} checks only for <em>absent</em>
 * required attributes, never for unknown ones. A regeneration that rewrites the file would drop the key,
 * by which point the field it describes has been dropped too and this test is already red.
 *
 * <p><b>Nothing here is a list of entity or field names.</b> The files are found by walking the
 * directory, the document by the {@code name} the file declares, and its fields by reflection. An entity
 * added later is covered on the day its definition file is written.
 */
class JhipsterEntityFieldsTest {

    /** Relative to the module directory, which is surefire's working directory. */
    private static final Path DEFINITIONS = Path.of(".jhipster");

    /** The second generator input, and item 15's hazard: a name here with no file resurrects an entity. */
    private static final Path YO_RC = Path.of(".yo-rc.json");

    /** Where the documents live; a definition file's {@code name} names a class in here. */
    private static final String DOMAIN_PACKAGE = "net.jojoaddison.domain";

    /** The key each definition file uses to declare a field JDL has no way to state. */
    private static final String INEXPRESSIBLE = "fieldsThisFormatCannotExpress";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------------------------------
    // The field comparison
    // -------------------------------------------------------------------------------------------------

    @Test
    void everyFieldOfEveryDocumentIsDescribedByItsGeneratorInput() {
        List<String> disagreements = new ArrayList<>();

        for (Path definition : definitionFiles()) {
            JsonNode entity = read(definition);
            Class<?> document = documentFor(entity, definition, disagreements);
            if (document == null) {
                continue;
            }
            Set<String> described = describedFieldNames(entity);
            Map<String, String> inexpressible = inexpressibleFields(entity);

            for (Field field : persistedFields(document)) {
                if (described.contains(field.getName()) || inexpressible.containsKey(field.getName())) {
                    continue;
                }
                disagreements.add(
                    "%s does not describe %s.%s (%s) and does not declare it under %s".formatted(
                            definition,
                            document.getSimpleName(),
                            field.getName(),
                            javaTypeName(field.getGenericType()),
                            INEXPRESSIBLE
                        )
                );
            }
        }

        assertThat(disagreements)
            .as(
                "Every field of a document must be described by the generator input that names it, or " +
                "declared there as one this format cannot express — regenerating the entity emits the " +
                "fields the file lists and silently drops the rest"
            )
            .isEmpty();
    }

    @Test
    void noGeneratorInputDescribesAFieldItsDocumentDoesNotHave() {
        List<String> disagreements = new ArrayList<>();

        for (Path definition : definitionFiles()) {
            JsonNode entity = read(definition);
            Class<?> document = documentFor(entity, definition, disagreements);
            if (document == null) {
                continue;
            }
            Set<String> present = persistedFields(document).stream().map(Field::getName).collect(Collectors.toSet());

            for (JsonNode field : entity.path("fields")) {
                String fieldName = field.path("fieldName").asText();
                if (fieldName.equals("id") || present.contains(fieldName)) {
                    continue;
                }
                disagreements.add(
                    "%s describes field '%s', which %s does not have".formatted(definition, fieldName, document.getSimpleName())
                );
            }
        }

        assertThat(disagreements)
            .as(
                "A generator input describing a field the document dropped is the same hazard read " +
                "backwards: regenerating puts the field back. This is how the retired Roster entity " +
                "would have returned (backlog.md item 15)"
            )
            .isEmpty();
    }

    @Test
    void everyDescribedFieldDeclaresTheJavaTypeTheDocumentActuallyUses() {
        List<String> disagreements = new ArrayList<>();

        for (Path definition : definitionFiles()) {
            JsonNode entity = read(definition);
            Class<?> document = documentFor(entity, definition, disagreements);
            if (document == null) {
                continue;
            }
            Map<String, Field> byName = persistedFields(document)
                .stream()
                .collect(Collectors.toMap(Field::getName, field -> field, (first, second) -> first, LinkedHashMap::new));
            Map<String, String> inexpressible = inexpressibleFields(entity);

            for (JsonNode field : entity.path("fields")) {
                String fieldName = field.path("fieldName").asText();
                Field declared = byName.get(fieldName);
                if (declared == null || inexpressible.containsKey(fieldName)) {
                    continue;
                }
                String expected = javaTypeFor(field.path("fieldType").asText());
                String actual = javaTypeName(declared.getGenericType());
                if (!expected.equals(actual)) {
                    disagreements.add(
                        "%s declares '%s' as %s, which is Java %s, but %s.%s is %s — and it is not declared under %s".formatted(
                                definition,
                                fieldName,
                                field.path("fieldType").asText(),
                                expected,
                                document.getSimpleName(),
                                fieldName,
                                actual,
                                INEXPRESSIBLE
                            )
                    );
                }
            }
        }

        assertThat(disagreements)
            .as(
                "A field described with the wrong type regenerates as that wrong type. Where the format " +
                "genuinely cannot state the real one, say so under %s with the reason rather than " +
                "leaving the approximation looking like a description",
                INEXPRESSIBLE
            )
            .isEmpty();
    }

    @Test
    void everyInexpressibleFieldNamedIsRealAndGivesAReason() {
        List<String> disagreements = new ArrayList<>();

        for (Path definition : definitionFiles()) {
            JsonNode entity = read(definition);
            Class<?> document = documentFor(entity, definition, disagreements);
            if (document == null) {
                continue;
            }
            Set<String> present = persistedFields(document).stream().map(Field::getName).collect(Collectors.toSet());

            inexpressibleFields(entity).forEach((fieldName, reason) -> {
                if (!present.contains(fieldName)) {
                    disagreements.add(
                        "%s declares '%s' under %s, but %s has no such field — remove the entry with the field".formatted(
                                definition,
                                fieldName,
                                INEXPRESSIBLE,
                                document.getSimpleName()
                            )
                    );
                } else if (reason.isBlank()) {
                    disagreements.add(
                        "%s declares '%s' under %s with no reason; the next reader needs to know why, not only that".formatted(
                                definition,
                                fieldName,
                                INEXPRESSIBLE
                            )
                    );
                }
            });
        }

        assertThat(disagreements)
            .as(
                "The %s entries are the only hand-maintained part of this comparison, so they are held " +
                "to naming a real field with a real reason — an exemption that outlives its field is a " +
                "note nobody will delete",
                INEXPRESSIBLE
            )
            .isEmpty();
    }

    // -------------------------------------------------------------------------------------------------
    // The second generator input
    // -------------------------------------------------------------------------------------------------

    @Test
    void theEntityListAndTheDefinitionFilesNameTheSameEntities() {
        Set<String> listed = new LinkedHashSet<>();
        for (JsonNode name : read(YO_RC).path("generator-jhipster").path("entities")) {
            listed.add(name.asText());
        }
        Set<String> defined = definitionFiles()
            .stream()
            .map(path -> read(path).path("name").asText())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(listed)
            .as(
                "%s's entity array and the definition files under %s are two halves of one generator " +
                "input. A name listed with no file leaves the next regeneration erroring or re-emitting " +
                "the entity from scratch — which is how the retired Roster would have come back " +
                "(backlog.md item 15) — and a file no name reaches is a definition nothing regenerates. " +
                "Documents written by hand (Absence, ProfessionalApplication, OnboardingEvent, and the " +
                "messaging ones) belong in neither half, which is the honest state for a document the " +
                "generator does not own",
                YO_RC,
                DEFINITIONS
            )
            .containsExactlyInAnyOrderElementsOf(defined);
    }

    @Test
    void theSweepFindsGeneratorInputsAndDocumentFieldsToCheck() {
        // A sweep that silently matches nothing passes forever. Floors, not totals: the exact numbers
        // would be the list of names this test exists to avoid.
        assertThat(definitionFiles()).as("entity definitions under %s", DEFINITIONS).isNotEmpty();
        assertThat(definitionFiles().stream().map(path -> read(path).path("name").asText()).map(JhipsterEntityFieldsTest::documentClass))
            .as("definition files whose document resolves in %s", DOMAIN_PACKAGE)
            .isNotEmpty()
            .doesNotContainNull();
    }

    // -------------------------------------------------------------------------------------------------
    // Reading the two sides
    // -------------------------------------------------------------------------------------------------

    private static List<Path> definitionFiles() {
        assertThat(DEFINITIONS)
            .as("run from the module directory: %s holds the JHipster entity definitions", DEFINITIONS.toAbsolutePath())
            .isDirectory();
        try (Stream<Path> entries = Files.list(DEFINITIONS)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".json")).sorted(Comparator.naturalOrder()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + DEFINITIONS.toAbsolutePath(), e);
        }
    }

    private static JsonNode read(Path path) {
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse " + path.toAbsolutePath(), e);
        }
    }

    /** The document a definition file names, or {@code null} — recording the absence as a disagreement. */
    private static Class<?> documentFor(JsonNode entity, Path definition, List<String> disagreements) {
        String name = entity.path("name").asText();
        Class<?> document = documentClass(name);
        if (document == null) {
            disagreements.add("%s names entity '%s', which is not a class in %s".formatted(definition, name, DOMAIN_PACKAGE));
        }
        return document;
    }

    private static Class<?> documentClass(String name) {
        try {
            return Class.forName(DOMAIN_PACKAGE + "." + name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * The document's own persisted fields: declared here rather than inherited, since JHipster's audit
     * base class is generated separately and appears in no definition file; instance rather than static,
     * which drops {@code serialVersionUID} and the coverage agent's synthetic members; and never the key,
     * which {@code fields} may or may not list.
     */
    private static List<Field> persistedFields(Class<?> document) {
        return Arrays.stream(document.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .filter(field -> !field.isSynthetic())
            .filter(field -> !field.getName().equals("id"))
            .toList();
    }

    /**
     * Every field name the generator would emit from this definition: the ones it lists, plus the
     * content-type companion it derives for a non-text blob.
     */
    private static Set<String> describedFieldNames(JsonNode entity) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode field : entity.path("fields")) {
            String fieldName = field.path("fieldName").asText();
            names.add(fieldName);
            if (hasDerivedContentType(field)) {
                names.add(fieldName + "ContentType");
            }
        }
        return names;
    }

    /**
     * {@code prepare-field.js}: a {@code byte[]} or {@code ByteBuffer} field gets a
     * {@code <fieldName>ContentType} companion unless its blob content is text.
     */
    private static boolean hasDerivedContentType(JsonNode field) {
        String fieldType = field.path("fieldType").asText();
        boolean binary =
            fieldType.equals("byte[]") ||
            fieldType.equals("ByteBuffer") ||
            fieldType.equals("Blob") ||
            fieldType.equals("AnyBlob") ||
            fieldType.equals("ImageBlob");
        return binary && !"text".equals(field.path("fieldTypeBlobContent").asText(null));
    }

    private static Map<String, String> inexpressibleFields(JsonNode entity) {
        Map<String, String> declared = new LinkedHashMap<>();
        JsonNode node = entity.path(INEXPRESSIBLE);
        node.fieldNames().forEachRemaining(fieldName -> declared.put(fieldName, node.path(fieldName).asText("")));
        return declared;
    }

    /**
     * The Java type a JDL field type generates. JDL names its types after the Java ones with four
     * exceptions, all of them blobs ({@code jdl/jhipster/field-types.js}); an enum's type is the enum's
     * own name and passes through.
     */
    private static String javaTypeFor(String jdlType) {
        return switch (jdlType) {
            case "Blob", "AnyBlob", "ImageBlob" -> "byte[]";
            case "TextBlob" -> "String";
            default -> jdlType;
        };
    }

    /** {@code List<String>} rather than {@code List}, so a message says which dimension is missing. */
    private static String javaTypeName(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterized) {
            String arguments = Arrays.stream(parameterized.getActualTypeArguments())
                .map(JhipsterEntityFieldsTest::javaTypeName)
                .collect(Collectors.joining(", "));
            return javaTypeName(parameterized.getRawType()) + "<" + arguments + ">";
        }
        return type.getTypeName();
    }
}
