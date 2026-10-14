package net.jojoaddison.service.dto;

public class PersonalDocumentDTO {

    private String id;
    private String profileId;
    private String name;
    private String type;
    private String data;
    private String dataContentType;
    private Long sizeBytes;
    private String otherLabel;

    public String getId() {
        return id;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getData() {
        return data;
    }

    public String getDataContentType() {
        return dataContentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getOtherLabel() {
        return otherLabel;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setData(String data) {
        this.data = data;
    }

    public void setDataContentType(String dataContentType) {
        this.dataContentType = dataContentType;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public void setOtherLabel(String otherLabel) {
        this.otherLabel = otherLabel;
    }
}
