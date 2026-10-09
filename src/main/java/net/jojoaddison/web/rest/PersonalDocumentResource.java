package net.jojoaddison.web.rest;

import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.service.PersonalDocumentService;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import net.jojoaddison.web.rest.util.LocationUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link net.jojoaddison.domain.PersonalDocument}.
 *
 * <h2>⛔ This resource has no {@code GET} mappings — which is NOT the same as having no reads</h2>
 *
 * <p>It carried three — {@code GET ""}, {@code GET /{id}} and {@code GET /profile/{profileId}} — and
 * every one of them returned {@code data} inline, because {@link net.jojoaddison.domain.PersonalDocument}
 * declares it as a bare {@code byte[]} with no {@code @JsonProperty} access restriction. This file has
 * never carried a {@code @PreAuthorize} anywhere in it, so all three fell through to
 * {@code /api/** -> .authenticated()} with <b>no owner check of any kind</b>. Measured on the quality
 * stack as the seeded {@code carer} — {@code ROLE_CARER}, an account holding no {@link
 * net.jojoaddison.domain.Profile} at all: {@code GET /api/personal-documents} answered <b>200 with
 * 2,401,251 bytes</b>, five documents under one profile that was not the caller's, every row carrying a
 * populated base64 {@code data} — CERTIFICATE, LICENSE, PASSPORT, GHANACARD, PASSPHOTO — and
 * {@code GET /api/personal-documents/{id}} answered <b>200 with 281,395 bytes</b>. So on that image any
 * of the eight clinical authorities could enumerate the collection and read any clinician's passport
 * and Ghana Card, and because {@code validate-origin: false} holds across the estate, so could any
 * hc-admin or hc-patient account over the shared signing key.
 *
 * <p><b>Nothing misbehaved, which is how it survived.</b> No {@code web/} or {@code mobile/} screen has
 * ever called the plural path. Every read a <em>product surface</em> makes is subject-scoped and
 * checked, and there are <b>two</b> such surfaces rather than one: an applicant's own list and the byte
 * stream are {@link OwnPersonalDocumentResource}'s on the <em>singular</em> base ({@code data} nulled
 * on the list, {@code assertOwnerOrReviewer} before the bytes), while a <b>reviewer's document list</b>
 * is {@code ProfessionalApplicationResource.applicationDocuments} on
 * {@code /api/professional-application/{id}/documents} — {@code assertAdminOrOwner}, with
 * {@code data} nulled on every row. Only the reviewer's byte <em>stream</em> comes through the singular
 * resource. The only caller the three deleted reads ever had was this resource's own test, and that
 * test asserted the 200 was correct: a green suite was part of the defect.
 *
 * <h2>⚠ The residue: {@code PATCH} is a read path, and it is row 227's, not this change's</h2>
 *
 * <p><b>Deleting every {@code @GetMapping} did not leave this resource unable to answer with a
 * document.</b> {@link #partialUpdatePersonalDocument} returns {@code ResponseEntity<PersonalDocument>}
 * through {@code wrapOrNotFound}, and {@code PersonalDocumentService.partialUpdate} merges the
 * non-null fields of the body onto the stored row and returns <em>that row</em> — so a merge-patch body
 * carrying nothing but {@code id} is a no-op whose response <b>is the whole document, {@code data}
 * included</b>. Measured on this branch, as {@code ROLE_NURSE} with no ownership relationship to a
 * seeded document under a foreign {@code profileId}: {@code PATCH} with {@code &#123;"id":"…"&#125;}
 * answered <b>200 carrying the base64 bytes</b>.
 *
 * <p>⭐ <b>What this change did and did not accomplish, stated exactly.</b> The deletion is not
 * cosmetic: the reach of a byte read fell from the eight clinical authorities to the <b>six</b> of
 * {@code AuthoritiesConstants.CLINICAL_MUTATION} — a carer, chemist or technician is now genuinely
 * refused 403 — and from <em>enumerate the whole collection</em> to <em>echo one document whose id you
 * already hold</em>, since {@code existsById} refuses an unknown id with a 400 and no surviving
 * mapping hands out ids. For {@code ROLE_ADMIN} the byte access is by design. So this is
 * <b>defence-in-depth residue, not a live enumerable hole</b> — and it is a claim about this resource's
 * observable behaviour that "no reads" would have hidden.
 *
 * <p>⛔ <b>Do not close it here.</b> An owner or {@code ROLE_ADMIN} check on the {@code PATCH} is
 * <b>backlog.md row 227</b>'s decision, with the {@code PUT} beside it as a different hazard: that one
 * goes through {@code update}, a whole-document {@code save}, so a body omitting {@code data}
 * <em>destroys</em> the bytes rather than echoing them — the review measured it answering
 * {@code "data":null} — which is the shape item 46 already records for {@code supersededAt}. No test
 * here asserts the echo works: a test that pins a defect in place is worse than a row that names it.
 *
 * <p>⚠ <b>Two of the three were live and the third never rolled.</b>
 * {@code GET /profile/{profileId}} and {@code PersonalDocumentService.findAllByProfileId} were added on
 * the onboarding refactor branch ({@code 762795b}, 2026-10-08) and answer {@code 404 No static
 * resource} on the deployed image, so deleting them prevents a regression rather than closing a hole.
 *
 * <h2>The writes stay, deliberately</h2>
 *
 * <p>{@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} are the only admin data-maintenance surface
 * for a document and are unchanged by this. Two readings were considered and rejected: <b>gating the
 * reads</b> with {@code @PreAuthorize} and an owner check, which would have left two gated read
 * surfaces and therefore two answers to "who may read a document"; and <b>retiring the resource</b>,
 * which would have taken the writes with it — including the whole-document {@code PUT} that
 * un-archives a row by omitting {@code supersededAt}, which is backlog.md item 46 and is deliberately
 * left where it is. See profile-addendum.md § 4 S1 and backlog.md row 226.
 *
 * <p>{@code ClinicalAuthorityMatrixIT} holds the absence of the three {@code GET} mappings with the
 * statuses that were measured for it, and holds the writes' refusals in the same run so a dead
 * application cannot read as a successful deletion.
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
     */
    @PostMapping("")
    public ResponseEntity<PersonalDocument> createPersonalDocument(@RequestBody PersonalDocument personalDocument) {
        log.debug("REST request to save PersonalDocument : {}", personalDocument);
        if (personalDocument.getId() != null) {
            throw new BadRequestAlertException("A new personalDocument cannot already have an ID", ENTITY_NAME, "idexists");
        }

        personalDocument = personalDocumentService.save(personalDocument);
        domainEventPublisher.publishEntityCreated(
            "PersonalDocument",
            personalDocument.getId(),
            personalDocument.getProfileId(),
            net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        return ResponseEntity.created(LocationUri.of(personalDocument.getId()))
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
     */
    @PutMapping("/{id}")
    public ResponseEntity<PersonalDocument> updatePersonalDocument(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody PersonalDocument personalDocument
    ) {
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
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<PersonalDocument> partialUpdatePersonalDocument(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody PersonalDocument personalDocument
    ) {
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

    // The three generated GET mappings stood here and are gone (S1, backlog.md row 226). Do not put
    // them back: the class javadoc records what they returned to whom, and names the two subject-scoped
    // resources that serve the reads a product surface makes. Regenerating this entity emits all three
    // again. ⚠ It also records that PATCH below still answers with a whole document, so "this resource
    // cannot be read" is false — row 227.

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
