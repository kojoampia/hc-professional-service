package net.jojoaddison.service.dto;

/**
 * A Data Transfer Object for adding a profile to a team.
 */
public class ProfileTeamDTO {

    private String profileId;
    private String teamId;

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }
}
