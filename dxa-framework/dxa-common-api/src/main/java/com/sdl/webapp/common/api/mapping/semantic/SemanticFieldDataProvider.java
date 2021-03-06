package com.sdl.webapp.common.api.mapping.semantic;

import com.sdl.webapp.common.api.mapping.semantic.config.SemanticField;
import org.springframework.core.convert.TypeDescriptor;

import java.util.Map;

/**
 * Semantic field data provider.
 * <p>
 * This provides the actual data for a field during semantic mapping.
 */
public interface SemanticFieldDataProvider {

    /**
     * Gets the data for a semantic field.
     *
     * @param semanticField The semantic field.
     * @param targetType    The expected type of the data to be returned.
     * @return A {@code FieldData} object that contains the field value and property data. The field value must be of a
     * type that is compatible with the specified target type.
     * @throws com.sdl.webapp.common.api.mapping.semantic.SemanticMappingException If an error occurs and the data for the field cannot be retrieved.
     */
    FieldData getFieldData(SemanticField semanticField, TypeDescriptor targetType) throws SemanticMappingException;

    /**
     * Gets the data for the semantic field with the special property name "_self".
     *
     * @param targetType The expected type of the data to be returned.
     * @return The data for the "_self" field, this must be an object of a type that is compatible with the specified
     * target type.
     * @throws com.sdl.webapp.common.api.mapping.semantic.SemanticMappingException If an error occurs and the data for the field cannot be retrieved.
     */
    Object getSelfFieldData(TypeDescriptor targetType) throws SemanticMappingException;

    /**
     * Gets the data for the semantic field with the special property name "_all".
     *
     * @return The data for the "_all" field.
     * @throws com.sdl.webapp.common.api.mapping.semantic.SemanticMappingException If an error occurs and the data for the field cannot be retrieved.
     */
    Map<String, String> getAllFieldData() throws SemanticMappingException;
}
