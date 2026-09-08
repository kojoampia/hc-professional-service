package net.jojoaddison.web.rest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.Category;
import net.jojoaddison.repository.CategoryRepository;
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
 * REST controller for managing {@link net.jojoaddison.domain.Category}.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryResource {

    private final Logger log = LoggerFactory.getLogger(CategoryResource.class);

    private static final String ENTITY_NAME = "professionalServiceCategory";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final CategoryRepository categoryRepository;

    private final DomainEventPublisher domainEventPublisher;

    public CategoryResource(CategoryRepository categoryRepository, DomainEventPublisher domainEventPublisher) {
        this.categoryRepository = categoryRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    /**
     * {@code POST  /categories} : Create a new category.
     *
     * @param category the category to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new category, or with status {@code 400 (Bad Request)} if the category has already an ID.
     */
    @PostMapping("")
    public ResponseEntity<Category> createCategory(@RequestBody Category category) {
        log.debug("REST request to save Category : {}", category);
        if (category.getId() != null) {
            throw new BadRequestAlertException("A new category cannot already have an ID", ENTITY_NAME, "idexists");
        }
        category = categoryRepository.save(category);
        domainEventPublisher.publishEntityCreated(
            "Category",
            category.getId(),
            null,
            net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        return ResponseEntity.created(LocationUri.of(category.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, category.getId()))
            .body(category);
    }

    /**
     * {@code PUT  /categories/:id} : Updates an existing category.
     *
     * @param id the id of the category to save.
     * @param category the category to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated category,
     * or with status {@code 400 (Bad Request)} if the category is not valid,
     * or with status {@code 500 (Internal Server Error)} if the category couldn't be updated.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Category> updateCategory(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody Category category
    ) {
        log.debug("REST request to update Category : {}, {}", id, category);
        if (category.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, category.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!categoryRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        category = categoryRepository.save(category);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, category.getId()))
            .body(category);
    }

    /**
     * {@code PATCH  /categories/:id} : Partial updates given fields of an existing category, field will ignore if it is null
     *
     * @param id the id of the category to save.
     * @param category the category to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated category,
     * or with status {@code 400 (Bad Request)} if the category is not valid,
     * or with status {@code 404 (Not Found)} if the category is not found,
     * or with status {@code 500 (Internal Server Error)} if the category couldn't be updated.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<Category> partialUpdateCategory(
        @PathVariable(value = "id", required = false) final String id,
        @RequestBody Category category
    ) {
        log.debug("REST request to partial update Category partially : {}, {}", id, category);
        if (category.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, category.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!categoryRepository.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<Category> result = categoryRepository
            .findById(category.getId())
            .map(existingCategory -> {
                if (category.getName() != null) {
                    existingCategory.setName(category.getName());
                }
                if (category.getDescription() != null) {
                    existingCategory.setDescription(category.getDescription());
                }
                if (category.getCreatedDate() != null) {
                    existingCategory.setCreatedDate(category.getCreatedDate());
                }
                if (category.getModifiedDate() != null) {
                    existingCategory.setModifiedDate(category.getModifiedDate());
                }
                if (category.getCreatedBy() != null) {
                    existingCategory.setCreatedBy(category.getCreatedBy());
                }
                if (category.getModifiedBy() != null) {
                    existingCategory.setModifiedBy(category.getModifiedBy());
                }

                return existingCategory;
            })
            .map(categoryRepository::save);

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, category.getId())
        );
    }

    /**
     * {@code GET  /categories} : get all the categories.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of categories in body.
     */
    @GetMapping("")
    public List<Category> getAllCategories() {
        log.debug("REST request to get all Categories");
        return categoryRepository.findAll();
    }

    /**
     * {@code GET  /categories/:id} : get the "id" category.
     *
     * @param id the id of the category to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the category, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Category> getCategory(@PathVariable("id") String id) {
        log.debug("REST request to get Category : {}", id);
        Optional<Category> category = categoryRepository.findById(id);
        return ResponseUtil.wrapOrNotFound(category);
    }
    /*
     * There is deliberately no DELETE here — backlog item 57, which is item 56 in a second place.
     *
     * A category is only ever pointed at. Profile.specialtyCategoryId is a lone String holding a
     * category id, and it is the whole of what this service knows about a clinician's discipline —
     * there is no name beside it and no second field to fall back on. The generated delete was a
     * bare deleteById with no cascade, so removing a category left every profile naming a discipline
     * that resolves to nothing, and neither the profile nor the caller was told.
     *
     * Unlike a profile delete there is no announcement gap to weigh against it: specialtyCategoryId
     * is in no ProfileStatus field and in no isComplete requirement, so hc-admin never hears about a
     * category either way. The whole defect is the dangling pointer.
     *
     * Nothing called it. Not web/ — its review screen types specialtyCategoryId in as free text and
     * has no catalogue screen at all — not mobile/, not deploy/, not quality/'s seed-data.py, which
     * reads /api/categories and never deletes, and not the sibling stacks: hc-admin's category
     * client is bound to its own adminservice, and the only thing it calls over here is
     * POST /api/duty-roster. It was reachable all the same, and by more than an administrator:
     * DELETE /api/** admits all six CLINICAL_MUTATION roles, and on the quality stack a ROLE_DOCTOR
     * token deleted a category with 204 and left it 404.
     *
     * If a category ever needs retiring, it comes back as a deliberate change: the cheap answer is
     * an archived flag rather than a delete, because a profile that still points at a retired
     * category is a fact worth rendering, and a profile that points at nothing is not.
     */
}
