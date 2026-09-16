package fruition.core.chat.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class SourceRefListJsonConverter implements AttributeConverter<List<SourceRef>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<SourceRef> attribute) {
        if (attribute == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("sourceRefs 직렬화 실패", e);
        }
    }

    @Override
    public List<SourceRef> convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            return OBJECT_MAPPER.readValue(dbData, new TypeReference<List<SourceRef>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("sourceRefs 역직렬화 실패", e);
        }
    }
}
