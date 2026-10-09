package net.jojoaddison.domain;

import java.io.Serializable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A GeographicSpace represents a named geographic area (e.g., neighbourhood, district, city).
 */
@Document(collection = "geographic_spaces")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class GeographicSpace implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("name")
    private String name;

    @Field("type")
    private String type;

    /**
     * The space that contains this one, or null at the root.
     *
     * <p>Containment was implicit before this field: a district and the city around it were two
     * unrelated rows that happened to be named after each other. Proximity — "same space, then same
     * parent, then same ancestor" — is a walk up this chain and cannot be computed without it.
     *
     * <p>An opaque id rather than a {@code @DBRef}, matching {@code Team.geographicSpaceIds} and
     * {@code Professional.homeSpaceId}, which are the only other references to this collection —
     * and hc-professional's {@code DutyRoster.geographicSpaceId}, which is opaque over there for
     * the same reason.
     * A {@code @DBRef} would also make every read of a leaf space load its whole ancestry, which is
     * the opposite of what the reference read below wants.
     *
     * <p>A parent naming no stored space is left alone: nothing in this service enforces referential
     * integrity between collections, and a walk that meets a missing id simply ends. What is
     * enforced is that the chain terminates — see {@code GeographicSpaceCycleGuard}.
     */
    @Field("parent_id")
    private String parentId;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public GeographicSpace id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public GeographicSpace name(String name) {
        this.setName(name);
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return this.type;
    }

    public GeographicSpace type(String type) {
        this.setType(type);
        return this;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getParentId() {
        return this.parentId;
    }

    public GeographicSpace parentId(String parentId) {
        this.setParentId(parentId);
        return this;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GeographicSpace)) {
            return false;
        }
        return getId() != null && getId().equals(((GeographicSpace) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "GeographicSpace{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", type='" + getType() + "'" +
            ", parentId='" + getParentId() + "'" +
            "}";
    }
}
