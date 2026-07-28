package net.jojoaddison.web.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.service.PersonalDocumentService;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link net.jojoaddison.domain.PersonalDocument}.
 */
@RestController
@RequestMapping("/api/personal-documents")
public class PersonalDocumentResource {

    private final Logger log = LoggerFactory.getLogger(PersonalDocumentResource.class);

    private static final String ENTITY_NAME = "professionalServicePersonalDocument";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final PersonalDocumentService personalDocumentService;

    private final PersonalDocumentRepository personalDocumentRepository;

    private final DomainEventPublisher domainEventPublisher;

    public PersonalDocumentResource(
        PersonalDocumentService personalDocumentService,
        PersonalDocumentRepository personalDocumentRepository,
        DomainEventPublisher domainEventPublisher
    ) {
        this.personalDocumentService = personalDocumentService;
        this.personalDocumentRepository = personalDocumentRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    /**
     * {@code POST  /personal-documents} : Create a new personalDocument.
     *
     * @param personalDocument the personalDocument to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new personalDocument, or with status {@code 400 (Bad Request)} if the personalDocument has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<PersonalDocument> createPersonalDocument(@RequestBody PersonalDocument personalDocument)
        throws URISyntaxException {
        log.debug("REST request to save PersonalDocument : {}", personalDocument);
        if (personalDocument.getId() != null) {
            throw new BadRequestAlertException("A new personalDocument cannot already have an ID", ENTITY_NAME, "idexists");
        }
        personalDocument = personalDocumentService.save(personalDocument);
        domainEventPublisher.publishEntityCreated(
            "PersonalDocument",
            personalDocument.getId(),
            null,
            net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        return ResponseEntity.created(new URI("/api/personal-documents/" + personalDocument.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, personalDocument.getId()))
            .body(personalDocument);
    }

    /**
     * {@code PUT  /personal-documents/:id} : Updates an existing personalDocument.
     *
     * @param id the id of the personalDocument to save.
     * @param personalDocument the personalDocument to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated personalDocument,
     * or with status {@code 400 (Bad Request)} if the personalDocument is not valid,
     * or with status {@code 500 (Internal Server Error)} if the personalDocument couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<PersonalDocument> updatePersonalDocument(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody PersonalDocument personalDocument
    ) throws URISyntaxException {
        log.debug("REST request to update PersonalDocument : {}, {}", id, personalDocument);
        if (personalDocument.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, personalDocument.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!personalDocumentRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        personalDocument = personalDocumentService.update(personalDocument);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, personalDocument.getId()))
            .body(personalDocument);
    }

    /**
     * {@code PATCH  /personal-documents/:id} : Partial updates given fields of an existing personalDocument, field will ignore if it is null
     *
     * @param id the id of the personalDocument to save.
     * @param personalDocument the personalDocument to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated personalDocument,
     * or with status {@code 400 (Bad Request)} if the personalDocument is not valid,
     * or with status {@code 404 (Not Found)} if the personalDocument is not found,
     * or with status {@code 500 (Internal Server Error)} if the personalDocument couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<PersonalDocument> partialUpdatePersonalDocument(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody PersonalDocument personalDocument
    ) throws URISyntaxException {
        log.debug("REST request to partial update PersonalDocument partially : {}, {}", id, personalDocument);
        if (personalDocument.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, personalDocument.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!personalDocumentRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<PersonalDocument> result = personalDocumentService.partialUpdate(personalDocument);

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, personalDocument.getId())
        );
    }

    /**
     * {@code GET  /personal-documents} : get all the personalDocuments.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of personalDocuments in body.
     */
    @GetMapping("")
    public List<PersonalDocument> getAllPersonalDocuments() {
        log.debug("REST request to get all PersonalDocuments");
        return personalDocumentService.findAll();
    }

    /**
     * {@code GET  /personal-documents/:id} : get the "id" personalDocument.
     *
     * @param id the id of the personalDocument to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the personalDocument, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PersonalDocument> getPersonalDocument(@PathVariable("id") String id) {
        log.debug("REST request to get PersonalDocument : {}", id);
        Optional<PersonalDocument> personalDocument = personalDocumentService.findOne(id);
        return ResponseUtil.wrapOrNotFound(personalDocument);
    }

    /**
     * {@code DELETE  /personal-documents/:id} : delete the "id" personalDocument.
     *
     * @param id the id of the personalDocument to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePersonalDocument(@PathVariable("id") String id) {
        log.debug("REST request to delete PersonalDocument : {}", id);
        personalDocumentService.delete(id);
        return ResponseEntity.noContent().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id)).build();
    }
}
