package net.jojoaddison.service.mapper;

import net.jojoaddison.domain.Address;
import net.jojoaddison.service.dto.AddressDTO;

public class AddressMapper {

    public static AddressDTO toDto(Address address) {
        if (address == null) {
            return null;
        }
        AddressDTO dto = new AddressDTO();
        dto.setId(address.getId());
        dto.setDigitalAddress(address.getDigitalAddress());
        dto.setStreetAddress(address.getStreetAddress());
        dto.setCity(address.getCity());
        dto.setTown(address.getTown());
        dto.setRegion(address.getRegion());
        dto.setDistrict(address.getDistrict());
        dto.setCountry(address.getCountry());
        return dto;
    }

    public static Address toEntity(AddressDTO dto) {
        if (dto == null) {
            return null;
        }
        Address address = new Address();
        address.setId(dto.getId());
        address.setDigitalAddress(dto.getDigitalAddress());
        address.setStreetAddress(dto.getStreetAddress());
        address.setCity(dto.getCity());
        address.setTown(dto.getTown());
        address.setRegion(dto.getRegion());
        address.setDistrict(dto.getDistrict());
        address.setCountry(dto.getCountry());
        return address;
    }
}
