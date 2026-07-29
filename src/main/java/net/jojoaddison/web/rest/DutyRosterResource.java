package net.jojoaddison.web.rest;

import jakarta.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Professional duty-roster assignments (professional-onboarding-workflow.md
 * § Duty roster, assignment-only decision): roster administrators (ROLE_ADMIN)
 * create and remove assignments; professionals read their own via /my. There
 * is deliberately no self-subscription endpoint.
 */
@RestController
@RequestMapping("/api/onboarding/duty-rosters")
public class DutyRosterResource {

    private static final Logger log = LoggerFactory.getLogger(DutyRosterResource.class);

    private final DutyRosterRepository dutyRosterRepository;
    private final ProfileRepository profileRepository;
    private final DomainEventPublisher domainEventPublisher;

    public DutyRosterResource(
        DutyRosterRepository dutyRosterRepository,
        ProfileRepository profileRepository,
        DomainEventPublisher domainEventPublisher
    ) {
        this.dutyRosterRepository = dutyRosterRepository;
        this.profileRepository = profileRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    @PostMapping
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<DutyRoster> assign(@Valid @RequestBody DutyRoster dutyRoster) throws URISyntaxException {
        log.debug("REST request to assign duty roster : {}", dutyRoster);
        if (dutyRoster.getId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A new assignment cannot already have an id");
        }
        if (profileRepository.findById(dutyRoster.getProfessionalId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown professional profile");
        }
        DutyRoster saved = dutyRosterRepository.save(dutyRoster);
        domainEventPublisher.publishEntityCreated("DutyRoster", saved.getId(), null, SecurityUtils.getCurrentUserLogin().orElse("system"));
        return ResponseEntity.created(new URI("/api/onboarding/duty-rosters/" + saved.getId())).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<Void> unassign(@PathVariable String id) {
        dutyRosterRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public List<DutyRoster> listAll() {
        return dutyRosterRepository.findAllByOrderByDateAscShiftAsc();
    }

    /** A professional's own assignments — read-only view per the assignment-only policy. */
    @GetMapping("/my")
    public List<DutyRoster> myAssignments() {
        String login = SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
        return profileRepository
            .findByAccountId(login)
            .map(profile -> dutyRosterRepository.findByProfessionalIdOrderByDateAscShiftAsc(profile.getId()))
            .orElse(List.of());
    }
}
