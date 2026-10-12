package com.hcl.jrsmeta.parse;

import java.util.List;

public record JrxmlModel(String queryLanguage, String queryText, List<Field> fields, List<Parameter> parameters,
                         List<Variable> variables, List<Dataset> subDatasets, List<String> subreportExpressions) {
    public record Field(String name, String javaClass) {}
    public record Parameter(String name, String javaClass, String defaultValueExpression) {}
    public record Variable(String name, String javaClass, String calculation, String expression) {}
    public record Dataset(String name, String queryLanguage, String queryText, List<Field> fields) {}
}
