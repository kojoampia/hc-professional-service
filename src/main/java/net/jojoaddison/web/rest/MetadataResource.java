package net.jojoaddison.web.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.domain.Metadata;
import net.jojoaddison.repository.MetadataRepository;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link net.jojoaddison.domain.Metadata}.
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataResource {

    private final Logger log = LoggerFactory.getLogger(MetadataResource.class);

    private static final String ENTITY_NAME = "professionalMsMetadata";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final MetadataRepository metadataRepository;

    public MetadataResource(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    /**
     * {@code POST  /metadata} : Create a new metadata.
     *
     * @param metadata the metadata to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new metadata, or with status {@code 400 (Bad Request)} if the metadata has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<Metadata> createMetadata(@RequestBody Metadata metadata) throws URISyntaxException {
        log.debug("REST request to save Metadata : {}", metadata);
        if (metadata.getId() != null) {
            throw new BadRequestAlertException("A new metadata cannot already have an ID", ENTITY_NAME, "idexists");
        }
        metadata = metadataRepository.save(metadata);
        return ResponseEntity.created(new URI("/api/metadata/" + metadata.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, metadata.getId()))
            .body(metadata);
    }

    /**
     * {@code PUT  /metadata/:id} : Updates an existing metadata.
     *
     * @param id the id of the metadata to save.
     * @param metadata the metadata to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated metadata,
     * or with status {@code 400 (Bad Request)} if the metadata is not valid,
     * or with status {@code 500 (Internal Server Error)} if the metadata couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Metadata> updateMetadata(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody Metadata metadata
    ) throws URISyntaxException {
        log.debug("REST request to update Metadata : {}, {}", id, metadata);
        if (metadata.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, metadata.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!metadataRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        metadata = metadataRepository.save(metadata);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, metadata.getId()))
            .body(metadata);
    }

    /**
     * {@code PATCH  /metadata/:id} : Partial updates given fields of an existing metadata, field will ignore if it is null
     *
     * @param id the id of the metadata to save.
     * @param metadata the metadata to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated metadata,
     * or with status {@code 400 (Bad Request)} if the metadata is not valid,
     * or with status {@code 404 (Not Found)} if the metadata is not found,
     * or with status {@code 500 (Internal Server Error)} if the metadata couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<Metadata> partialUpdateMetadata(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody Metadata metadata
    ) throws URISyntaxException {
        log.debug("REST request to partial update Metadata partially : {}, {}", id, metadata);
        if (metadata.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, metadata.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!metadataRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<Metadata> result = metadataRepository
            .findById(metadata.getId())
            .map(existingMetadata -> {
                if (metadata.getCreatedBy() != null) {
                    existingMetadata.setCreatedBy(metadata.getCreatedBy());
                }
                if (metadata.getModifiedBy() != null) {
                    existingMetadata.setModifiedBy(metadata.getModifiedBy());
                }
                if (metadata.getCreatedDate() != null) {
                    existingMetadata.setCreatedDate(metadata.getCreatedDate());
                }
                if (metadata.getModifiedDate() != null) {
                    existingMetadata.setModifiedDate(metadata.getModifiedDate());
                }
                if (metadata.getData() != null) {
                    existingMetadata.setData(metadata.getData());
                }

                return existingMetadata;
            })
            .map(metadataRepository::save);

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, metadata.getId())
        );
    }

    /**
     * {@code GET  /metadata} : get all the metadata.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of metadata in body.
     */
    @GetMapping("")
    public List<Metadata> getAllMetadata() {
        log.debug("REST request to get all Metadata");
        return metadataRepository.findAll();
    }

    /**
     * {@code GET  /metadata/:id} : get the "id" metadata.
     *
     * @param id the id of the metadata to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the metadata, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Metadata> getMetadata(@PathVariable("id") String id) {
        log.debug("REST request to get Metadata : {}", id);
        Optional<Metadata> metadata = metadataRepository.findById(id);
        return ResponseUtil.wrapOrNotFound(metadata);
    }

    /**
     * {@code DELETE  /metadata/:id} : delete the "id" metadata.
     *
     * @param id the id of the metadata to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMetadata(@PathVariable("id") String id) {
        log.debug("REST request to delete Metadata : {}", id);
        metadataRepository.deleteById(id);
        return ResponseEntity.noContent().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id)).build();
    }
}
