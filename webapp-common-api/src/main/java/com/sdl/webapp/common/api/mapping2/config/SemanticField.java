package com.sdl.webapp.common.api.mapping2.config;

import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

public final class SemanticField {

    private final String name;

    private final String path;

    private final boolean multiValue;

    private final Map<FieldSemantics, SemanticField> embeddedFields;

    public SemanticField(String name, String path, boolean multiValue,
                         Map<FieldSemantics, SemanticField> embeddedFields) {
        this.name = name;
        this.path = path;
        this.multiValue = multiValue;
        this.embeddedFields = ImmutableMap.copyOf(embeddedFields);
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isMultiValue() {
        return multiValue;
    }

    public Map<FieldSemantics, SemanticField> getEmbeddedFields() {
        return embeddedFields;
    }

    @Override
    public String toString() {
        return "SemanticField{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", multiValue=" + multiValue +
                ", embeddedFields=" + embeddedFields +
                '}';
    }
}