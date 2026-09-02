package net.jojoaddison.domain.enumeration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The JHipster entity definitions in {@code .jhipster/} must declare exactly the enum values the code
 * has (backlog.md item 8).
 *
 * <p>Those files are <b>generator inputs</b>, not documentation. A value that lives on in one of them
 * is not merely stale: the next {@code jhipster entity} run emits the enum from {@code fieldValues},
 * so a retired value comes back. {@code ShiftType} lost {@code MORNING} and {@code AFTERNOON} in DR1
 * because the five-value set overlapped, its javadoc says not to reintroduce them, and
 * {@code DutyRoster.json} went on declaring all five for a fortnight with nothing failing to build —
 * which is the whole reason this test exists. The same shape bit the retired {@code Roster} entity,
 * where {@code .yo-rc.json} would have resurrected what {@code .jhipster/Roster.json}'s deletion
 * removed (item 15).
 *
 * <p><b>Nothing here is a list of names.</b> The files are found by walking the directory, the fields
 * by looking for {@code fieldValues}, the enum by the {@code fieldType} it names, and the expectation
 * by {@link Class#getEnumConstants()}. Adding a value to any enum in this package fails this test
 * until its generator input is updated, and adding a whole new enum-typed field to any entity is
 * covered on the day it is written, with nobody having edited this file. A test that names its own
 * coverage stops covering things: hc-admin had eight endpoints go unpaginated for a fortnight behind
 * a test asserting a literal list of twenty-three paths.
 *
 * <p><b>Order is part of the contract</b>, not only membership — the generator emits the constants in
 * the order {@code fieldValues} lists them, so a set comparison would pass on an input that
 * regenerates a differently-ordered enum.
 */
class JhipsterEnumFieldValuesTest {

    /** Where the generated Java enums live; {@code fieldType} names a class in here. */
    private static final String ENUMERATION_PACKAGE = "net.jojoaddison.domain.enumeration";

    /** Relative to the module directory, which is surefire's working directory. */
    private static final Path DEFINITIONS = Path.of(".jhipster");

    /** One {@code fieldValues} declaration: which file and field it came from, and what it says. */
    private record EnumField(String file, String fieldName, String enumType, List<String> declaredValues) {}

    @Test
    void everyEnumFieldInAGeneratorInputDeclaresExactlyItsEnumsValues() {
        List<String> disagreements = new ArrayList<>();

        for (EnumField field : enumFields()) {
            List<String> actual = enumValues(field.enumType());
            if (actual == null) {
                disagreements.add(
                    "%s field '%s' has fieldType %s, which is not an enum in %s".formatted(
                            field.file(),
                            field.fieldName(),
                            field.enumType(),
                            ENUMERATION_PACKAGE
                        )
                );
            } else if (!actual.equals(field.declaredValues())) {
                disagreements.add(
                    "%s field '%s' declares %s but %s has %s".formatted(
                            field.file(),
                            field.fieldName(),
                            field.declaredValues(),
                            field.enumType(),
                            actual
                        )
                );
            }
        }

        assertThat(disagreements)
            .as(
                "Generator inputs under %s must declare exactly the values of the enum they name — " +
                "regenerating the entity emits the enum from fieldValues, so a disagreement here " +
                "reintroduces or drops values in code",
                DEFINITIONS
            )
            .isEmpty();
    }

    @Test
    void theSweepFindsGeneratorInputsAndEnumFieldsToCheck() {
        // A sweep that silently matches nothing passes forever. Both counts are floors, not totals:
        // asserting the exact numbers would be the list of names this test exists to avoid.
        assertThat(definitionFiles()).as("entity definitions under %s", DEFINITIONS).isNotEmpty();
        assertThat(enumFields()).as("fields declaring fieldValues in %s", DEFINITIONS).isNotEmpty();
    }

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

    private static List<EnumField> enumFields() {
        ObjectMapper mapper = new ObjectMapper();
        List<EnumField> fields = new ArrayList<>();

        for (Path definition : definitionFiles()) {
            JsonNode entity;
            try {
                entity = mapper.readTree(definition.toFile());
            } catch (IOException e) {
                throw new IllegalStateException("Could not parse " + definition, e);
            }
            for (JsonNode field : entity.path("fields")) {
                // fieldValues is present exactly on enum-typed fields, whatever the enum is called.
                if (field.hasNonNull("fieldValues")) {
                    fields.add(
                        new EnumField(
                            definition.toString(),
                            field.path("fieldName").asText(),
                            field.path("fieldType").asText(),
                            Arrays.stream(field.path("fieldValues").asText().split(","))
                                .map(String::trim)
                                .filter(v -> !v.isEmpty())
                                .toList()
                        )
                    );
                }
            }
        }
        return fields;
    }

    /** The enum's constants in declaration order, or {@code null} if {@code fieldType} names no enum. */
    private static List<String> enumValues(String enumType) {
        Class<?> type;
        try {
            type = Class.forName(ENUMERATION_PACKAGE + "." + enumType);
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object[] constants = type.getEnumConstants();
        return constants == null ? null : Arrays.stream(constants).map(constant -> ((Enum<?>) constant).name()).toList();
    }
}
