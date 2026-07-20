package net.jojoaddison.web.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.domain.IDocument;
import net.jojoaddison.repository.IDocumentRepository;
import net.jojoaddison.service.IDocumentService;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link net.jojoaddison.domain.IDocument}.
 */
@RestController
@RequestMapping("/api/hc-documents")
public class IDocumentResource {

    private final Logger log = LoggerFactory.getLogger(IDocumentResource.class);

    private static final String ENTITY_NAME = "professionalServiceHcDocument";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final IDocumentService hCDocumentService;

    private final IDocumentRepository iDocumentRepository;

    public IDocumentResource(IDocumentService hCDocumentService, IDocumentRepository iDocumentRepository) {
        this.hCDocumentService = hCDocumentService;
        this.iDocumentRepository = iDocumentRepository;
    }

    /**
     * {@code POST  /hc-documents} : Create a new hCDocument.
     *
     * @param hCDocument the hCDocument to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new hCDocument, or with status {@code 400 (Bad Request)} if the hCDocument has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<IDocument> createHCDocument(@RequestBody IDocument hCDocument) throws URISyntaxException {
        log.debug("REST request to save HCDocument : {}", hCDocument);
        if (hCDocument.getId() != null) {
            throw new BadRequestAlertException("A new hCDocument cannot already have an ID", ENTITY_NAME, "idexists");
        }
        hCDocument = hCDocumentService.save(hCDocument);
        return ResponseEntity.created(new URI("/api/hc-documents/" + hCDocument.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, hCDocument.getId()))
            .body(hCDocument);
    }

    /**
     * {@code PUT  /hc-documents/:id} : Updates an existing hCDocument.
     *
     * @param id the id of the hCDocument to save.
     * @param hCDocument the hCDocument to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated hCDocument,
     * or with status {@code 400 (Bad Request)} if the hCDocument is not valid,
     * or with status {@code 500 (Internal Server Error)} if the hCDocument couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<IDocument> updateHCDocument(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody IDocument hCDocument
    ) throws URISyntaxException {
        log.debug("REST request to update HCDocument : {}, {}", id, hCDocument);
        if (hCDocument.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, hCDocument.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!iDocumentRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        hCDocument = hCDocumentService.update(hCDocument);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, hCDocument.getId()))
            .body(hCDocument);
    }

    /**
     * {@code PATCH  /hc-documents/:id} : Partial updates given fields of an existing hCDocument, field will ignore if it is null
     *
     * @param id the id of the hCDocument to save.
     * @param hCDocument the hCDocument to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated hCDocument,
     * or with status {@code 400 (Bad Request)} if the hCDocument is not valid,
     * or with status {@code 404 (Not Found)} if the hCDocument is not found,
     * or with status {@code 500 (Internal Server Error)} if the hCDocument couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<IDocument> partialUpdateHCDocument(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody IDocument hCDocument
    ) throws URISyntaxException {
        log.debug("REST request to partial update HCDocument partially : {}, {}", id, hCDocument);
        if (hCDocument.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, hCDocument.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!iDocumentRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<IDocument> result = hCDocumentService.partialUpdate(hCDocument);

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, hCDocument.getId())
        );
    }

    /**
     * {@code GET  /hc-documents} : get all the hCDocuments.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of hCDocuments in body.
     */
    @GetMapping("")
    public List<IDocument> getAllHCDocuments() {
        log.debug("REST request to get all HCDocuments");
        return hCDocumentService.findAll();
    }

    /**
     * {@code GET  /hc-documents/:id} : get the "id" hCDocument.
     *
     * @param id the id of the hCDocument to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the hCDocument, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<IDocument> getHCDocument(@PathVariable("id") String id) {
        log.debug("REST request to get HCDocument : {}", id);
        Optional<IDocument> hCDocument = hCDocumentService.findOne(id);
        return ResponseUtil.wrapOrNotFound(hCDocument);
    }

    /**
     * {@code DELETE  /hc-documents/:id} : delete the "id" hCDocument.
     *
     * @param id the id of the hCDocument to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHCDocument(@PathVariable("id") String id) {
        log.debug("REST request to delete HCDocument : {}", id);
        hCDocumentService.delete(id);
        return ResponseEntity.noContent().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id)).build();
    }
}
