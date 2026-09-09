package net.jojoaddison.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.CategoryRepository;
import net.jojoaddison.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A category or team id written onto a {@link Profile} must name a row that exists
 * (backlog.md item 60, second half).
 *
 * <p><b>Why this is a separate object rather than a private method.</b> Two write paths store these
 * two pointers — {@code OnboardingService.assignOrganization}, the admin-only assignment, and
 * {@code ProfileService.update}, the whole-document replace behind {@code PUT /api/profiles/&#123;id&#125;}
 * — and they live in different services. The rule duplicated once is the rule that gets fixed in one
 * place, which is the failure mode {@code ReferenceDataDeletionIT} was written to describe: the same
 * orphan reachable from a second collection nobody swept for.
 *
 * <p><b>What it does not do is repair.</b> Pointers that were already dangling before this existed
 * are left alone — see {@link #requireIntroducedReferencesResolve} — because item 60 filed the write
 * side and item 57 filed the delete side, and neither filed the backfill.
 *
 * <p>{@code PATCH /api/profiles/&#123;id&#125;} is absent from the callers on purpose: it refuses
 * both fields outright, so it has no write of theirs to validate. See
 * {@code ProfileResource.PATCH_REFUSED_FIELDS}.
 */
@Service
public class OrganizationReferenceValidator {

    private final CategoryRepository categoryRepository;

    private final TeamRepository teamRepository;

    public OrganizationReferenceValidator(CategoryRepository categoryRepository, TeamRepository teamRepository) {
        this.categoryRepository = categoryRepository;
        this.teamRepository = teamRepository;
    }

    /**
     * Every id named here must resolve, whatever the profile already held.
     *
     * <p>For the deliberate assignment path. An admin naming a category that does not exist has made
     * a mistake worth being told about even if that same wrong value is already stored, because the
     * whole point of the call is to decide what the value should be.
     *
     * <p>A null or blank {@code specialtyCategoryId}, and a null or empty {@code teamIds}, name
     * nothing and are therefore not a pointer to nothing. That is the ordinary case rather than an
     * edge: the quality stack holds zero categories (backlog.md item 61), so every professional
     * seeded there is assigned with no specialty at all.
     */
    public void requireReferencesResolve(String specialtyCategoryId, Collection<String> teamIds) {
        List<String> unresolved = new ArrayList<>();
        if (namesSomething(specialtyCategoryId) && !categoryRepository.existsById(specialtyCategoryId)) {
            unresolved.add("specialtyCategoryId=" + specialtyCategoryId);
        }
        if (teamIds != null) {
            teamIds
                .stream()
                .filter(OrganizationReferenceValidator::namesSomething)
                .filter(teamId -> !teamRepository.existsById(teamId))
                .forEach(teamId -> unresolved.add("teamIds=" + teamId));
        }
        if (!unresolved.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "These references name no existing row: " + String.join(", ", unresolved)
            );
        }
    }

    /**
     * Only what this write introduces must resolve; what was already stored is left alone.
     *
     * <p>For the whole-document replace behind {@code PUT /api/profiles/&#123;id&#125;}, where a
     * caller has no way to <em>not</em> carry a field forward — omitting it clears it. Validating the
     * resulting state rather than the change would make a profile that already holds a pointer to
     * nothing permanently uneditable through that endpoint, punishing whoever touches it next for a
     * value somebody else wrote. Repairing those is not item 60.
     *
     * @param incoming the profile as the request body describes it.
     * @param stored the profile as it is on disk, or {@code null} if there is none.
     */
    public void requireIntroducedReferencesResolve(Profile incoming, Profile stored) {
        String category = Objects.equals(incoming.getSpecialtyCategoryId(), stored == null ? null : stored.getSpecialtyCategoryId())
            ? null
            : incoming.getSpecialtyCategoryId();
        List<String> storedTeams = stored == null ? List.of() : orEmpty(stored.getTeamIds());
        List<String> introducedTeams = orEmpty(incoming.getTeamIds()).stream().filter(teamId -> !storedTeams.contains(teamId)).toList();
        requireReferencesResolve(category, introducedTeams);
    }

    private static boolean namesSomething(String id) {
        return id != null && !id.isBlank();
    }

    private static List<String> orEmpty(List<String> ids) {
        return ids == null ? List.of() : ids;
    }
}
