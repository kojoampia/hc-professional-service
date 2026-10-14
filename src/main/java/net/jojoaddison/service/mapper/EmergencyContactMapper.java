package net.jojoaddison.service.mapper;

import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.service.dto.EmergencyContactDTO;

public class EmergencyContactMapper {

    public static EmergencyContactDTO toDto(EmergencyContact contact) {
        if (contact == null) {
            return null;
        }
        EmergencyContactDTO dto = new EmergencyContactDTO();
        dto.setId(contact.getId());
        dto.setName(contact.getName());
        dto.setRelationship(contact.getRelationship());
        dto.setPhoneNumber(contact.getPhone());
        dto.setEmail(contact.getEmail());
        dto.setAddress(AddressMapper.toDto(contact.getAddress()));
        return dto;
    }

    public static EmergencyContact toEntity(EmergencyContactDTO dto) {
        if (dto == null) {
            return null;
        }
        EmergencyContact contact = new EmergencyContact();
        contact.setId(dto.getId());
        contact.setName(dto.getName());
        contact.setRelationship(dto.getRelationship());
        contact.setPhone(dto.getPhoneNumber());
        contact.setEmail(dto.getEmail());
        contact.setAddress(AddressMapper.toEntity(dto.getAddress()));
        return contact;
    }

    public static List<EmergencyContactDTO> toDtoList(List<EmergencyContact> contacts) {
        if (contacts == null) {
            return null;
        }
        List<EmergencyContactDTO> dtos = new ArrayList<>();
        for (EmergencyContact contact : contacts) {
            dtos.add(toDto(contact));
        }
        return dtos;
    }

    public static List<EmergencyContact> toEntityList(List<EmergencyContactDTO> dtos) {
        if (dtos == null) {
            return null;
        }
        List<EmergencyContact> contacts = new ArrayList<>();
        for (EmergencyContactDTO dto : dtos) {
            contacts.add(toEntity(dto));
        }
        return contacts;
    }
}
