/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.dsl.processor.parser;

import static com.oracle.truffle.dsl.processor.java.ElementUtils.*;

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.dsl.processor.java.*;
import com.oracle.truffle.dsl.processor.model.*;

public final class MethodSpecParser {

    private boolean emitErrors = true;
    private boolean useVarArgs = false;

    private final Template template;

    public MethodSpecParser(Template template) {
        this.template = template;
    }

    public Template getTemplate() {
        return template;
    }

    public TypeSystemData getTypeSystem() {
        return template.getTypeSystem();
    }

    public boolean isEmitErrors() {
        return emitErrors;
    }

    public boolean isUseVarArgs() {
        return useVarArgs;
    }

    public void setEmitErrors(boolean emitErrors) {
        this.emitErrors = emitErrors;
    }

    public void setUseVarArgs(boolean useVarArgs) {
        this.useVarArgs = useVarArgs;
    }

    public TemplateMethod parse(MethodSpec methodSpecification, ExecutableElement method, AnnotationMirror annotation, int naturalOrder) {
        if (methodSpecification == null) {
            return null;
        }

        methodSpecification.applyTypeDefinitions("types");

        String id = method.getSimpleName().toString();
        TypeMirror returnType = method.getReturnType();
        List<TypeMirror> parameterTypes = new ArrayList<>();
        for (VariableElement var : method.getParameters()) {
            parameterTypes.add(var.asType());
        }

        TemplateMethod templateMethod = parseImpl(methodSpecification, naturalOrder, id, method, annotation, returnType, parameterTypes);
        if (templateMethod != null) {
            for (int i = 0; i < templateMethod.getParameters().size(); i++) {
                if (i < method.getParameters().size()) {
                    templateMethod.getParameters().get(i).setVariableElement(method.getParameters().get(i));
                }
            }
        }
        return templateMethod;
    }

    public TemplateMethod parseImpl(MethodSpec methodSpecification, int naturalOrder, String id, ExecutableElement method, AnnotationMirror annotation, TypeMirror returnType,
                    List<TypeMirror> parameterTypes) {
        ParameterSpec returnTypeSpec = methodSpecification.getReturnType();
        Parameter returnTypeMirror = matchParameter(returnTypeSpec, returnType, -1, -1);
        if (returnTypeMirror == null) {
            if (emitErrors) {
                TemplateMethod invalidMethod = new TemplateMethod(id, naturalOrder, template, methodSpecification, method, annotation, returnTypeMirror, Collections.<Parameter> emptyList());
                String expectedReturnType = returnTypeSpec.toSignatureString(true);
                String actualReturnType = ElementUtils.getSimpleName(returnType);

                String message = String.format("The provided return type \"%s\" does not match expected return type \"%s\".\nExpected signature: \n %s", actualReturnType, expectedReturnType,
                                methodSpecification.toSignatureString(method.getSimpleName().toString()));
                invalidMethod.addError(message);
                return invalidMethod;
            } else {
                return null;
            }
        }

        List<Parameter> parameters = parseParameters(methodSpecification, parameterTypes, isUseVarArgs() && method != null ? method.isVarArgs() : false);
        if (parameters == null) {
            if (isEmitErrors() && method != null) {
                TemplateMethod invalidMethod = new TemplateMethod(id, naturalOrder, template, methodSpecification, method, annotation, returnTypeMirror, Collections.<Parameter> emptyList());
                String message = String.format("Method signature %s does not match to the expected signature: \n%s", createActualSignature(method),
                                methodSpecification.toSignatureString(method.getSimpleName().toString()));
                invalidMethod.addError(message);
                return invalidMethod;
            } else {
                return null;
            }
        }

        return new TemplateMethod(id, naturalOrder, template, methodSpecification, method, annotation, returnTypeMirror, parameters);
    }

    private static String createActualSignature(ExecutableElement method) {
        StringBuilder b = new StringBuilder("(");
        String sep = "";
        if (method != null) {
            for (VariableElement var : method.getParameters()) {
                b.append(sep);
                b.append(ElementUtils.getSimpleName(var.asType()));
                sep = ", ";
            }
        }
        b.append(")");
        return b.toString();
    }

    /*
     * Parameter parsing tries to parse required arguments starting from offset 0 with increasing
     * offset until it finds a signature end that matches the required specification. If there is no
     * end matching the required arguments, parsing fails. Parameters prior to the parsed required
     * ones are cut and used to parse the optional parameters.
     */
    private List<Parameter> parseParameters(MethodSpec spec, List<TypeMirror> parameterTypes, boolean varArgs) {
        List<Parameter> parsedRequired = null;
        int offset = 0;
        for (; offset <= parameterTypes.size(); offset++) {
            List<TypeMirror> parameters = new ArrayList<>();
            parameters.addAll(parameterTypes.subList(offset, parameterTypes.size()));
            parsedRequired = parseParametersRequired(spec, parameters, varArgs);
            if (parsedRequired != null) {
                break;
            }
        }

        if (parsedRequired == null) {
            return null;
        }

        if (parsedRequired.isEmpty() && offset == 0) {
            offset = parameterTypes.size();
        }
        List<TypeMirror> potentialOptionals = parameterTypes.subList(0, offset);
        List<Parameter> parsedOptionals = parseParametersOptional(spec, potentialOptionals);
        if (parsedOptionals == null) {
            return null;
        }

        List<Parameter> finalParameters = new ArrayList<>();
        finalParameters.addAll(parsedOptionals);
        finalParameters.addAll(parsedRequired);
        return finalParameters;
    }

    private List<Parameter> parseParametersOptional(MethodSpec spec, List<TypeMirror> types) {
        List<Parameter> parsedParams = new ArrayList<>();

        int typeStartIndex = 0;
        List<ParameterSpec> specifications = spec.getOptional();
        outer: for (int specIndex = 0; specIndex < specifications.size(); specIndex++) {
            ParameterSpec specification = specifications.get(specIndex);
            for (int typeIndex = typeStartIndex; typeIndex < types.size(); typeIndex++) {
                TypeMirror actualType = types.get(typeIndex);
                Parameter optionalParam = matchParameter(specification, actualType, -1, -1);
                if (optionalParam != null) {
                    parsedParams.add(optionalParam);
                    typeStartIndex = typeIndex + 1;
                    continue outer;
                }
            }
        }

        if (typeStartIndex < types.size()) {
            // not enough types found
            return null;
        }
        return parsedParams;
    }

    private List<Parameter> parseParametersRequired(MethodSpec spec, List<TypeMirror> types, boolean typeVarArgs) {
        List<Parameter> parsedParams = new ArrayList<>();
        List<ParameterSpec> specifications = spec.getRequired();
        boolean specVarArgs = spec.isVariableRequiredParameters();
        int typeIndex = 0;
        int specificationIndex = 0;

        ParameterSpec specification;
        while ((specification = nextSpecification(specifications, specificationIndex, specVarArgs)) != null) {
            TypeMirror actualType = nextActualType(types, typeIndex, typeVarArgs);
            if (actualType == null) {
                if (spec.isIgnoreAdditionalSpecifications()) {
                    break;
                }
                return null;
            }

            int typeVarArgsIndex = typeVarArgs ? typeIndex - types.size() + 1 : -1;
            int specVarArgsIndex = specVarArgs ? specificationIndex - specifications.size() + 1 : -1;

            if (typeVarArgsIndex >= 0 && specVarArgsIndex >= 0) {
                // both specifications and types have a variable number of arguments
                // we would get into an endless loop if we would continue
                break;
            }

            Parameter resolvedParameter = matchParameter(specification, actualType, specVarArgsIndex, typeVarArgsIndex);
            if (resolvedParameter == null) {
                return null;
            }
            parsedParams.add(resolvedParameter);
            typeIndex++;
            specificationIndex++;
        }

        if (typeIndex < types.size()) {
            // additional types available
            if (spec.isIgnoreAdditionalParameters()) {
                return parsedParams;
            } else {
                return null;
            }
        }

        return parsedParams;
    }

    private static ParameterSpec nextSpecification(List<ParameterSpec> specifications, int specIndex, boolean varArgs) {
        if (varArgs && specIndex >= specifications.size() - 1 && !specifications.isEmpty()) {
            return specifications.get(specifications.size() - 1);
        } else if (specIndex < specifications.size()) {
            return specifications.get(specIndex);
        } else {
            return null;
        }
    }

    private static TypeMirror nextActualType(List<TypeMirror> types, int typeIndex, boolean varArgs) {
        if (varArgs && typeIndex >= types.size() - 1 && !types.isEmpty()) {
            // unpack varargs array argument
            TypeMirror actualType = types.get(types.size() - 1);
            if (actualType.getKind() == TypeKind.ARRAY) {
                actualType = ((ArrayType) actualType).getComponentType();
            }
            return actualType;
        } else if (typeIndex < types.size()) {
            return types.get(typeIndex);
        } else {
            return null;
        }
    }

    private Parameter matchParameter(ParameterSpec specification, TypeMirror mirror, int specificationIndex, int varArgsIndex) {
        TypeMirror resolvedType = mirror;
        if (hasError(resolvedType)) {
            return null;
        }

        if (!specification.matches(resolvedType)) {
            return null;
        }

        TypeData resolvedTypeData = getTypeSystem().findTypeData(resolvedType);
        if (resolvedTypeData != null) {
            return new Parameter(specification, resolvedTypeData, specificationIndex, varArgsIndex);
        } else {
            return new Parameter(specification, resolvedType, specificationIndex, varArgsIndex);
        }
    }

}
