package net.jojoaddison.service.mapper;

import java.time.LocalDate;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.Sex;
import net.jojoaddison.service.dto.ProfileDTO;

/**
 * A Mapper for the Profile entity and its DTO ProfileDTO.
 */
public class ProfileMapper {

    public static ProfileDTO toDto(Profile profile) {
        if (profile == null) {
            return null;
        }
        ProfileDTO dto = new ProfileDTO();
        dto.setId(profile.getId());
        dto.setAccountId(profile.getAccountId());
        dto.setFirstName(profile.getFirstName());
        dto.setLastName(profile.getLastName());
        dto.setBirthDate(profile.getBirthDate().toString());
        dto.setSex(String.valueOf(profile.getSex().ordinal()));
        dto.setCardType(String.valueOf(profile.getCardType()));
        dto.setCardNumber(profile.getCardNumber());
        dto.setPhoneNumber(profile.getPhoneNumber());
        dto.setMobileNumber(profile.getMobilePhone());
        dto.setEmail(profile.getEmail());
        dto.setAddress(AddressMapper.toDto(profile.getAddress()));
        dto.setContacts(EmergencyContactMapper.toDtoList(profile.getContacts()));
        dto.setStatus(String.valueOf(profile.getStatus()));
        return dto;
    }

    public static Profile toEntity(ProfileDTO dto) {
        if (dto == null) {
            return null;
        }
        Profile profile = new Profile();
        profile.setId(dto.getId());
        profile.setAccountId(dto.getAccountId());
        profile.setFirstName(dto.getFirstName());
        profile.setLastName(dto.getLastName());
        // Absent means "not provided", never a parse: PUT /api/profile is a partial write per pane,
        // and applyProvidedFields must see null so the stored value survives a body that did not
        // carry the field — OwnPersonalDocumentAvatarIT writes a two-field body onto the row the
        // avatar created. A present-but-malformed value still throws (OwnProfilePathIT holds the
        // unparseable birthDate to a 5xx).
        profile.setBirthDate(dto.getBirthDate() == null ? null : LocalDate.parse(dto.getBirthDate()));
        profile.setSex(dto.getSex() == null ? null : Sex.valueOf(dto.getSex()));
        profile.setCardType(dto.getCardType() == null ? null : DocumentType.valueOf(dto.getCardType()));
        profile.setCardNumber(dto.getCardNumber());
        profile.setPhoneNumber(dto.getPhoneNumber());
        profile.setMobilePhone(dto.getMobileNumber());
        profile.setEmail(dto.getEmail());
        profile.setAddress(AddressMapper.toEntity(dto.getAddress()));
        profile.setContacts(EmergencyContactMapper.toEntityList(dto.getContacts()));
        profile.setStatus(dto.getStatus() == null ? null : ProfileStatus.valueOf(dto.getStatus()));
        return profile;
    }
}
