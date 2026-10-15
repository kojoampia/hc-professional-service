package net.jojoaddison.service.dto;

public class SpecialCategoryDTO {

    private String profileId;

    private String categoryId;

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    @Override
    public String toString() {
        return "SpecialCategoryDTO{" + "profileId='" + profileId + '\'' + ", categoryId='" + categoryId + '\'' + '}';
    }
}
