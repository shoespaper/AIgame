package com.gameneko.aigame.engine;

import java.util.List;

/** Engine-neutral description of one action the selected engine can resolve. */
public record SupportedAction(String type, String description, List<Parameter> parameters) {
    public SupportedAction {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Supported action type is required");
        type = type.trim();
        description = description == null ? "" : description;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** 描述某个引擎动作所需的单个参数。 */
    /** @param name 参数名 @param valueType 取值类型 @param description 参数说明 @param required 是否必填 */
    public record Parameter(String name, String valueType, String description, boolean required) {
        public Parameter {
            if (name == null || name.isBlank() || valueType == null || valueType.isBlank()) {
                throw new IllegalArgumentException("Parameter name and value type are required");
            }
            name = name.trim();
            valueType = valueType.trim();
            description = description == null ? "" : description;
        }
    }
}
