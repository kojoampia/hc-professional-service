package net.jojoaddison.domain;

import jakarta.validation.constraints.*;
import java.io.Serial;
import java.io.Serializable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A Hub.
 */
@Document(collection = "hub")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class Hub implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @NotNull
    @Size(max = 60)
    @Field("name")
    private String name;

    @Min(value = 0)
    @Field("staff_count")
    private Integer staffCount;

    @DBRef
    @Field("address")
    private Address address;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public Hub id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public Hub name(String name) {
        this.setName(name);
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getStaffCount() {
        return this.staffCount;
    }

    public Hub staffCount(Integer staffCount) {
        this.setStaffCount(staffCount);
        return this;
    }

    public void setStaffCount(Integer staffCount) {
        this.staffCount = staffCount;
    }

    public Address getAddress() {
        return this.address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public Hub address(Address address) {
        this.setAddress(address);
        return this;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hub)) {
            return false;
        }
        return getId() != null && getId().equals(((Hub) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Hub{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", staffCount=" + getStaffCount() +
            "}";
    }
}
