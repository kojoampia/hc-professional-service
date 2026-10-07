package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.LocalDate;
import net.jojoaddison.domain.enumeration.UnavailabilityReason;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Embedded document representing a period of professional unavailability.
 */
public class UnavailabilityPeriod implements Serializable {

    private static final long serialVersionUID = 1L;

    @Field("reason")
    private UnavailabilityReason reason;

    @Field("from_date")
    private LocalDate fromDate;

    @Field("to_date")
    private LocalDate toDate;

    public UnavailabilityReason getReason() {
        return reason;
    }

    public UnavailabilityPeriod reason(UnavailabilityReason reason) {
        this.reason = reason;
        return this;
    }

    public void setReason(UnavailabilityReason reason) {
        this.reason = reason;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public UnavailabilityPeriod fromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
        return this;
    }

    public void setFromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public UnavailabilityPeriod toDate(LocalDate toDate) {
        this.toDate = toDate;
        return this;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    @Override
    public String toString() {
        return (
            "UnavailabilityPeriod{" +
            "reason='" +
            getReason() +
            "'" +
            ", fromDate='" +
            getFromDate() +
            "'" +
            ", toDate='" +
            getToDate() +
            "'" +
            "}"
        );
    }
}
