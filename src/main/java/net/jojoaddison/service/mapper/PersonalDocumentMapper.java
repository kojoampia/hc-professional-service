package net.jojoaddison.service.mapper;

import java.util.Base64;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.service.dto.PersonalDocumentDTO;

public class PersonalDocumentMapper {

    public static PersonalDocumentDTO toDto(PersonalDocument personalDocument) {
        if (personalDocument == null) {
            return null;
        }
        PersonalDocumentDTO dto = new PersonalDocumentDTO();
        dto.setId(personalDocument.getId());
        dto.setProfileId(personalDocument.getProfileId());
        dto.setName(personalDocument.getName());
        dto.setType(String.valueOf(personalDocument.getType()));
        dto.setData(Base64.getEncoder().encodeToString(personalDocument.getData()));
        dto.setDataContentType(personalDocument.getDataContentType());
        dto.setSizeBytes(personalDocument.getSizeBytes());
        dto.setOtherLabel(personalDocument.getOtherLabel());
        return dto;
    }

    public static PersonalDocument toEntity(PersonalDocumentDTO dto) {
        if (dto == null) {
            return null;
        }
        PersonalDocument personalDocument = new PersonalDocument();
        personalDocument.setId(dto.getId());
        personalDocument.setProfileId(dto.getProfileId());
        personalDocument.setName(dto.getName());
        personalDocument.setType(DocumentType.valueOf(dto.getType()));
        personalDocument.setData(Base64.getDecoder().decode(dto.getData()));
        personalDocument.setDataContentType(dto.getDataContentType());
        personalDocument.setSizeBytes(dto.getSizeBytes());
        personalDocument.setOtherLabel(dto.getOtherLabel());
        return personalDocument;
    }
}
