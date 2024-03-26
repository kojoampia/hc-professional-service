package net.jojoaddison.web.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.domain.HCPayOption;
import net.jojoaddison.repository.HCPayOptionRepository;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link net.jojoaddison.domain.HCPayOption}.
 */
@RestController
@RequestMapping("/api/hc-pay-options")
public class HCPayOptionResource {

    private final Logger log = LoggerFactory.getLogger(HCPayOptionResource.class);

    private static final String ENTITY_NAME = "professionalMshcPayOption";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final HCPayOptionRepository hCPayOptionRepository;

    public HCPayOptionResource(HCPayOptionRepository hCPayOptionRepository) {
        this.hCPayOptionRepository = hCPayOptionRepository;
    }

    /**
     * {@code POST  /hc-pay-options} : Create a new hCPayOption.
     *
     * @param hCPayOption the hCPayOption to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new hCPayOption, or with status {@code 400 (Bad Request)} if the hCPayOption has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<HCPayOption> createHCPayOption(@RequestBody HCPayOption hCPayOption) throws URISyntaxException {
        log.debug("REST request to save HCPayOption : {}", hCPayOption);
        if (hCPayOption.getId() != null) {
            throw new BadRequestAlertException("A new hCPayOption cannot already have an ID", ENTITY_NAME, "idexists");
        }
        HCPayOption result = hCPayOptionRepository.save(hCPayOption);
        return ResponseEntity
            .created(new URI("/api/hc-pay-options/" + result.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, result.getId()))
            .body(result);
    }

    /**
     * {@code PUT  /hc-pay-options/:id} : Updates an existing hCPayOption.
     *
     * @param id the id of the hCPayOption to save.
     * @param hCPayOption the hCPayOption to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated hCPayOption,
     * or with status {@code 400 (Bad Request)} if the hCPayOption is not valid,
     * or with status {@code 500 (Internal Server Error)} if the hCPayOption couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<HCPayOption> updateHCPayOption(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody HCPayOption hCPayOption
    ) throws URISyntaxException {
        log.debug("REST request to update HCPayOption : {}, {}", id, hCPayOption);
        if (hCPayOption.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, hCPayOption.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!hCPayOptionRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        HCPayOption result = hCPayOptionRepository.save(hCPayOption);
        return ResponseEntity
            .ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, hCPayOption.getId()))
            .body(result);
    }

    /**
     * {@code PATCH  /hc-pay-options/:id} : Partial updates given fields of an existing hCPayOption, field will ignore if it is null
     *
     * @param id the id of the hCPayOption to save.
     * @param hCPayOption the hCPayOption to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated hCPayOption,
     * or with status {@code 400 (Bad Request)} if the hCPayOption is not valid,
     * or with status {@code 404 (Not Found)} if the hCPayOption is not found,
     * or with status {@code 500 (Internal Server Error)} if the hCPayOption couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<HCPayOption> partialUpdateHCPayOption(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody HCPayOption hCPayOption
    ) throws URISyntaxException {
        log.debug("REST request to partial update HCPayOption partially : {}, {}", id, hCPayOption);
        if (hCPayOption.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, hCPayOption.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!hCPayOptionRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<HCPayOption> result = hCPayOptionRepository
            .findById(hCPayOption.getId())
            .map(existingHCPayOption -> {
                if (hCPayOption.getType() != null) {
                    existingHCPayOption.setType(hCPayOption.getType());
                }
                if (hCPayOption.getUserID() != null) {
                    existingHCPayOption.setUserID(hCPayOption.getUserID());
                }
                if (hCPayOption.getMetadata() != null) {
                    existingHCPayOption.setMetadata(hCPayOption.getMetadata());
                }

                return existingHCPayOption;
            })
            .map(hCPayOptionRepository::save);

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, hCPayOption.getId())
        );
    }

    /**
     * {@code GET  /hc-pay-options} : get all the hCPayOptions.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of hCPayOptions in body.
     */
    @GetMapping("")
    public List<HCPayOption> getAllHCPayOptions() {
        log.debug("REST request to get all HCPayOptions");
        return hCPayOptionRepository.findAll();
    }

    /**
     * {@code GET  /hc-pay-options/:id} : get the "id" hCPayOption.
     *
     * @param id the id of the hCPayOption to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the hCPayOption, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<HCPayOption> getHCPayOption(@PathVariable("id") String id) {
        log.debug("REST request to get HCPayOption : {}", id);
        Optional<HCPayOption> hCPayOption = hCPayOptionRepository.findById(id);
        return ResponseUtil.wrapOrNotFound(hCPayOption);
    }

    /**
     * {@code DELETE  /hc-pay-options/:id} : delete the "id" hCPayOption.
     *
     * @param id the id of the hCPayOption to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHCPayOption(@PathVariable("id") String id) {
        log.debug("REST request to delete HCPayOption : {}", id);
        hCPayOptionRepository.deleteById(id);
        return ResponseEntity.noContent().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id)).build();
    }

    /**
     * {@code SEARCH  /hc-pay-options/_search?query=:query} : search for the hCPayOption corresponding
     * to the query.
     *
     * @param query the query of the hCPayOption search.
     * @return the result of the search.
     */
    @GetMapping("/_search")
    public List<HCPayOption> searchHCPayOptions(@RequestParam("query") String query) {
        log.debug("REST request to search HCPayOptions for query {}", query);
        try {
            return hCPayOptionRepository.search(query);
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
