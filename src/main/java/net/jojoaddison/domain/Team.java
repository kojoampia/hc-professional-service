package net.jojoaddison.domain;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A Team.
 */
@Document(collection = "team")
public class Team implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("name")
    private String name;

    @Field("description")
    private String description;

    @Field("members")
    private Set<Profile> members = new HashSet<>();

    @Field("supervisor")
    private Profile supervisor;

    @Field("manager")
    private Profile manager;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public Team id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public Team name(String name) {
        this.setName(name);
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public Team description(String description) {
        this.setDescription(description);
        return this;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Profile> getMembers() {
        return this.members;
    }

    public Team members(Set<Profile> members) {
        this.setMembers(members);
        return this;
    }

    public void setMembers(Set<Profile> members) {
        this.members = members;
    }

    public Profile getSupervisor() {
        return this.supervisor;
    }

    public Team supervisor(Profile supervisor) {
        this.setSupervisor(supervisor);
        return this;
    }

    public void setSupervisor(Profile supervisor) {
        this.supervisor = supervisor;
    }

    public Profile getManager() {
        return this.manager;
    }

    public Team manager(Profile manager) {
        this.setManager(manager);
        return this;
    }

    public void setManager(Profile manager) {
        this.manager = manager;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Team)) {
            return false;
        }
        return getId() != null && getId().equals(((Team) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Team{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", description='" + getDescription() + "'" +
            ", members='" + getMembers() + "'" +
            ", supervisor='" + getSupervisor() + "'" +
            ", manager='" + getManager() + "'" +
            "}";
    }
}
