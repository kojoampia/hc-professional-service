package net.jojoaddison.web.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.Roster;
import net.jojoaddison.repository.RosterRepository;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link net.jojoaddison.domain.Roster}.
 */
@RestController
@RequestMapping("/api/rosters")
public class RosterResource {

    private final Logger log = LoggerFactory.getLogger(RosterResource.class);

    private static final String ENTITY_NAME = "professionalServiceRoster";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final RosterRepository rosterRepository;

    private final DomainEventPublisher domainEventPublisher;

    public RosterResource(RosterRepository rosterRepository, DomainEventPublisher domainEventPublisher) {
        this.rosterRepository = rosterRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    /**
     * {@code POST  /rosters} : Create a new roster.
     *
     * @param roster the roster to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new roster, or with status {@code 400 (Bad Request)} if the roster has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<Roster> createRoster(@RequestBody Roster roster) throws URISyntaxException {
        log.debug("REST request to save Roster : {}", roster);
        if (roster.getId() != null) {
            throw new BadRequestAlertException("A new roster cannot already have an ID", ENTITY_NAME, "idexists");
        }
        roster = rosterRepository.save(roster);
        domainEventPublisher.publishEntityCreated(
            "Roster",
            roster.getId(),
            null,
            net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        return ResponseEntity.created(new URI("/api/rosters/" + roster.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, roster.getId()))
            .body(roster);
    }

    /**
     * {@code PUT  /rosters/:id} : Updates an existing roster.
     *
     * @param id the id of the roster to save.
     * @param roster the roster to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated roster,
     * or with status {@code 400 (Bad Request)} if the roster is not valid,
     * or with status {@code 500 (Internal Server Error)} if the roster couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Roster> updateRoster(@PathVariable(value = "id", required = false) final String id, @RequestBody Roster roster)
        throws URISyntaxException {
        log.debug("REST request to update Roster : {}, {}", id, roster);
        if (roster.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, roster.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!rosterRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        roster = rosterRepository.save(roster);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, roster.getId()))
            .body(roster);
    }

    /**
     * {@code PATCH  /rosters/:id} : Partial updates given fields of an existing roster, field will ignore if it is null
     *
     * @param id the id of the roster to save.
     * @param roster the roster to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated roster,
     * or with status {@code 400 (Bad Request)} if the roster is not valid,
     * or with status {@code 404 (Not Found)} if the roster is not found,
     * or with status {@code 500 (Internal Server Error)} if the roster couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<Roster> partialUpdateRoster(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody Roster roster
    ) throws URISyntaxException {
        log.debug("REST request to partial update Roster partially : {}, {}", id, roster);
        if (roster.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, roster.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!rosterRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<Roster> result = rosterRepository
            .findById(roster.getId())
            .map(existingRoster -> {
                if (roster.getName() != null) {
                    existingRoster.setName(roster.getName());
                }
                if (roster.getDescription() != null) {
                    existingRoster.setDescription(roster.getDescription());
                }
                if (roster.getProfessionalId() != null) {
                    existingRoster.setProfessionalId(roster.getProfessionalId());
                }
                if (roster.getSchedule() != null) {
                    existingRoster.setSchedule(roster.getSchedule());
                }
                if (roster.getDuration() != null) {
                    existingRoster.setDuration(roster.getDuration());
                }
                if (roster.getTasks() != null) {
                    existingRoster.setTasks(roster.getTasks());
                }
                if (roster.getCreatedDate() != null) {
                    existingRoster.setCreatedDate(roster.getCreatedDate());
                }
                if (roster.getModifiedDate() != null) {
                    existingRoster.setModifiedDate(roster.getModifiedDate());
                }
                if (roster.getCreatedBy() != null) {
                    existingRoster.setCreatedBy(roster.getCreatedBy());
                }
                if (roster.getModifiedBy() != null) {
                    existingRoster.setModifiedBy(roster.getModifiedBy());
                }

                return existingRoster;
            })
            .map(rosterRepository::save);

        return ResponseUtil.wrapOrNotFound(result, HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, roster.getId()));
    }

    /**
     * {@code GET  /rosters} : get all the rosters.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of rosters in body.
     */
    @GetMapping("")
    public List<Roster> getAllRosters() {
        log.debug("REST request to get all Rosters");
        return rosterRepository.findAll();
    }

    /**
     * {@code GET  /rosters/:id} : get the "id" roster.
     *
     * @param id the id of the roster to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the roster, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Roster> getRoster(@PathVariable("id") String id) {
        log.debug("REST request to get Roster : {}", id);
        Optional<Roster> roster = rosterRepository.findById(id);
        return ResponseUtil.wrapOrNotFound(roster);
    }

    /**
     * {@code DELETE  /rosters/:id} : delete the "id" roster.
     *
     * @param id the id of the roster to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoster(@PathVariable("id") String id) {
        log.debug("REST request to delete Roster : {}", id);
        rosterRepository.deleteById(id);
        return ResponseEntity.noContent().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id)).build();
    }
}
