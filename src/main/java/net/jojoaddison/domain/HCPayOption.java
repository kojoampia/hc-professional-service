package net.jojoaddison.domain;

import java.io.Serializable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A HCPayOption.
 */
@Document(collection = "hcpay_option")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class HCPayOption implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("type")
    private String type;

    @Field("user_id")
    private String userID;

    @Field("metadata")
    private String metadata;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public HCPayOption id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return this.type;
    }

    public HCPayOption type(String type) {
        this.setType(type);
        return this;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUserID() {
        return this.userID;
    }

    public HCPayOption userID(String userID) {
        this.setUserID(userID);
        return this;
    }

    public void setUserID(String userID) {
        this.userID = userID;
    }

    public String getMetadata() {
        return this.metadata;
    }

    public HCPayOption metadata(String metadata) {
        this.setMetadata(metadata);
        return this;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HCPayOption)) {
            return false;
        }
        return getId() != null && getId().equals(((HCPayOption) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "HCPayOption{" +
            "id=" + getId() +
            ", type='" + getType() + "'" +
            ", userID='" + getUserID() + "'" +
            ", metadata='" + getMetadata() + "'" +
            "}";
    }
}
