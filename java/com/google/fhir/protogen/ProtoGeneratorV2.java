//    Copyright 2023 Google Inc.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        https://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package com.google.fhir.protogen;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Streams.stream;
import static com.google.fhir.protogen.GeneratorUtils.getElementById;
import static com.google.fhir.protogen.GeneratorUtils.isProfile;
import static com.google.fhir.protogen.GeneratorUtils.lastIdToken;
import static com.google.fhir.protogen.GeneratorUtils.nameFromQualifiedName;
import static com.google.fhir.protogen.GeneratorUtils.toFieldNameCase;
import static com.google.fhir.protogen.GeneratorUtils.toFieldTypeCase;
import static java.util.stream.Collectors.toCollection;

import com.google.common.base.Ascii;
import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.fhir.common.AnnotationUtils;
import com.google.fhir.common.Codes;
import com.google.fhir.common.Extensions;
import com.google.fhir.common.InvalidFhirException;
import com.google.fhir.proto.Annotations;
import com.google.fhir.proto.ProtoGeneratorAnnotations;
import com.google.fhir.proto.ProtogenConfig;
import com.google.fhir.protogen.GeneratorUtils.QualifiedType;
import com.google.fhir.protogen.ValueSetGeneratorV2.BoundCodeGenerator;
import com.google.fhir.r4.core.BindingStrengthCode;
import com.google.fhir.r4.core.Canonical;
import com.google.fhir.r4.core.ConstraintSeverityCode;
import com.google.fhir.r4.core.ElementDefinition;
import com.google.fhir.r4.core.Extension;
import com.google.fhir.r4.core.ResourceTypeCode;
import com.google.fhir.r4.core.SearchParameter;
import com.google.fhir.r4.core.SlicingRulesCode;
import com.google.fhir.r4.core.StructureDefinition;
import com.google.fhir.r4.core.StructureDefinitionKindCode;
import com.google.fhir.r4.core.TypeDerivationRuleCode;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Message;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.nullness.Nullable;

/** A class which turns FHIR StructureDefinitions into protocol messages. */
// TODO(b/244184211): Move a bunch of the public static methods into ProtoGeneratorUtils.
public class ProtoGeneratorV2 {

  public static final String REGEX_EXTENSION_URL = "http://hl7.org/fhir/StructureDefinition/regex";
  public static final String EXPLICIT_TYPE_NAME_EXTENSION_URL =
      "http://hl7.org/fhir/StructureDefinition/structuredefinition-explicit-type-name";

  // Map of time-like primitive type ids to supported granularity
  private static final ImmutableMap<String, List<String>> TIME_LIKE_PRECISION_MAP =
      ImmutableMap.of(
          "date", ImmutableList.of("YEAR", "MONTH", "DAY"),
          "dateTime",
              ImmutableList.of("YEAR", "MONTH", "DAY", "SECOND", "MILLISECOND", "MICROSECOND"),
          "instant", ImmutableList.of("SECOND", "MILLISECOND", "MICROSECOND"),
          "time", ImmutableList.of("SECOND", "MILLISECOND", "MICROSECOND"));
  private static final ImmutableSet<String> TYPES_WITH_TIMEZONE =
      ImmutableSet.of("date", "dateTime", "instant");

  // Certain field names are reserved symbols in various languages.
  private static final ImmutableSet<String> RESERVED_FIELD_NAMES =
      ImmutableSet.of("assert", "for", "hasAnswer", "package", "string", "class");

  private static final EnumDescriptorProto PRECISION_ENUM =
      EnumDescriptorProto.newBuilder()
          .setName("Precision")
          .addValue(
              EnumValueDescriptorProto.newBuilder()
                  .setName("PRECISION_UNSPECIFIED")
                  .setNumber(0)
                  .build())
          .build();

  private static final FieldDescriptorProto TIMEZONE_FIELD =
      FieldDescriptorProto.newBuilder()
          .setName("timezone")
          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(FieldDescriptorProto.Type.TYPE_STRING)
          .setNumber(2)
          .build();

  private static final ImmutableMap<String, FieldDescriptorProto.Type> PRIMITIVE_TYPE_OVERRIDES =
      ImmutableMap.of(
          "base64Binary", FieldDescriptorProto.Type.TYPE_BYTES,
          "boolean", FieldDescriptorProto.Type.TYPE_BOOL,
          "integer", FieldDescriptorProto.Type.TYPE_SINT32,
          "positiveInt", FieldDescriptorProto.Type.TYPE_UINT32,
          "unsignedInt", FieldDescriptorProto.Type.TYPE_UINT32);

  // Exclude constraints from the DomainResource until we refactor
  // them to a common place rather on every resource.
  // TODO(b/244184211): remove these with the above refactoring.
  private static final ImmutableSet<String> DOMAIN_RESOURCE_CONSTRAINTS =
      ImmutableSet.of(
          "contained.contained.empty()",
          "contained.meta.versionId.empty() and contained.meta.lastUpdated.empty()",
          "contained.where((('#'+id in (%resource.descendants().reference"
              + " | %resource.descendants().as(canonical) | %resource.descendants().as(uri)"
              + " | %resource.descendants().as(url))) or descendants().where(reference = '#')"
              + ".exists() or descendants().where(as(canonical) = '#').exists() or"
              + " descendants().where(as(canonical) = '#').exists()).not())"
              + ".trace('unmatched', id).empty()",
          "text.div.exists()",
          "text.`div`.exists()",
          "contained.meta.security.empty()");

  // FHIR elements may have core constraint definitions that do not add
  // value to protocol buffers, so we exclude them.
  private static final ImmutableSet<String> EXCLUDED_FHIR_CONSTRAINTS =
      ImmutableSet.<String>builder()
          .addAll(DOMAIN_RESOURCE_CONSTRAINTS)
          .add(
              "extension.exists() != value.exists()",
              "hasValue() | (children().count() > id.count())",
              "hasValue() or (children().count() > id.count())",
              "hasValue() or (children().count() > id.count()) or $this is Parameters",
              // Exclude the FHIR-provided element name regex, since field names are known at
              // compile time
              "path.matches('[^\\\\s\\\\.,:;\\\\\\'\"\\\\/|?!@#$%&*()\\\\[\\\\]{}]{1,64}"
                  + "(\\\\.[^\\\\s\\\\.,:;\\\\\\'\"\\\\/|?!@#$%&*()\\\\[\\\\]{}]{1,64}"
                  + "(\\\\[x\\\\])?(\\\\:[^\\\\s\\\\.]+)?)*')",
              "path.matches('^[^\\\\s\\\\.,:;\\\\\\'\"\\\\/|?!@#$%&*()\\\\[\\\\]{}]{1,64}"
                  + "(\\\\.[^\\\\s\\\\.,:;\\\\\\'\"\\\\/|?!@#$%&*()\\\\[\\\\]{}]{1,64}"
                  + "(\\\\[x\\\\])?(\\\\:[^\\\\s\\\\.]+)?)*$')",
              // "telcom or endpoint" is an invalid expression that shows up in USCore
              "telecom or endpoint",
              "fullUrl.contains('/_history/').not()", // See https://jira.hl7.org/browse/FHIR-25525
              // Invalid FHIRPath constraint on StructureDefinition.snapshot in STU3. Fixed in R4
              // but unlikely to be backported.
              "element.all(definition and min and max)",
              // See https://jira.hl7.org/browse/FHIR-25796
              "probability is decimal implies (probability as decimal) <= 100")
          .build();

  private static final String FHIRPATH_TYPE_PREFIX = "http://hl7.org/fhirpath/";

  private static final String FHIR_TYPE_EXTENSION_URL =
      "http://hl7.org/fhir/StructureDefinition/structuredefinition-fhir-type";

  // Should we use custom types for constrained references?
  private static final boolean USE_TYPED_REFERENCES = false;

  // Mapping from urls for StructureDefinition to data about that StructureDefinition.
  private final ImmutableMap<String, StructureDefinitionData> structDefDataByUrl;
  // Mapping from id to StructureDefinition data about all known base (i.e., not profile) types.
  private final ImmutableMap<String, StructureDefinition> baseStructDefsById;

  private static Set<String> getTypesDefinedInType(Descriptor type) {
    Set<String> types = new HashSet<>();
    types.add(type.getFullName());
    for (Descriptor subType : type.getNestedTypes()) {
      types.addAll(getTypesDefinedInType(subType));
    }
    for (EnumDescriptor enumType : type.getEnumTypes()) {
      types.add(enumType.getFullName());
    }
    return types;
  }

  // The package to write new protos to.
  private final ProtogenConfig protogenConfig;
  private final FhirPackage inputPackage;

  private final BoundCodeGenerator boundCodeGenerator;
  private final Map<ResourceTypeCode.Value, List<SearchParameter>> searchParameterMap =
      new HashMap<>();

  private static class StructureDefinitionData {
    final StructureDefinition structDef;
    final String inlineType;

    StructureDefinitionData(StructureDefinition structDef, String inlineType) {
      this.structDef = structDef;
      this.inlineType = inlineType;
    }
  }

  public ProtoGeneratorV2(
      ProtogenConfig protogenConfig,
      FhirPackage inputPackage,
      BoundCodeGenerator boundCodeGenerator) {
    this.protogenConfig = protogenConfig;
    this.inputPackage = inputPackage;
    this.boundCodeGenerator = boundCodeGenerator;

    Map<StructureDefinition, String> allDefinitions = new HashMap<>();

    allDefinitions.putAll(
        stream(inputPackage.structureDefinitions().iterator())
            .collect(toImmutableMap(def -> def, def -> protogenConfig.getProtoPackage())));
    searchParameterMap.putAll(
        GeneratorUtils.getSearchParameterMap(
            ImmutableList.copyOf(inputPackage.searchParameters())));

    Map<String, StructureDefinitionData> mutableStructDefDataByUrl = new HashMap<>();
    for (Map.Entry<StructureDefinition, String> knownType : allDefinitions.entrySet()) {
      StructureDefinition def = knownType.getKey();
      String url = def.getUrl().getValue();
      if (url.isEmpty()) {
        throw new IllegalArgumentException(
            "Invalid FHIR structure definition: " + def.getId().getValue() + " has no url");
      }

      StructureDefinitionData structDefData = new StructureDefinitionData(def, getTypeName(def));
      mutableStructDefDataByUrl.put(def.getUrl().getValue(), structDefData);
    }
    this.structDefDataByUrl = ImmutableMap.copyOf(mutableStructDefDataByUrl);

    // Used to ensure each structure definition is unique by url.
    final Set<String> knownUrls = new HashSet<>();

    this.baseStructDefsById =
        allDefinitions.keySet().stream()
            .filter(
                def -> def.getDerivation().getValue() != TypeDerivationRuleCode.Value.CONSTRAINT)
            .filter(def -> knownUrls.add(def.getUrl().getValue()))
            .collect(toImmutableMap(def -> def.getId().getValue(), def -> def));
  }

  // Given a structure definition, gets the name of the top-level message that will be generated.
  private String getTypeName(StructureDefinition def) {
    return GeneratorUtils.getTypeName(def);
  }

  private StructureDefinitionData getDefinitionDataByUrl(String url) {
    if (!structDefDataByUrl.containsKey(url)) {
      throw new IllegalArgumentException("Unrecognized resource URL: " + url);
    }
    return structDefDataByUrl.get(url);
  }

  /**
   * Generate a proto descriptor from a StructureDefinition, using the snapshot form of the
   * definition. For a more elaborate discussion of these versions, see
   * https://www.hl7.org/fhir/structuredefinition.html.
   */
  public DescriptorProto generateProto(StructureDefinition def) throws InvalidFhirException {
    DescriptorProto generatedProto = new PerDefinitionGenerator(def).generate();

    return generatedProto;
  }

  private StructureDefinition fixIdBug(StructureDefinition def) {
    boolean isResource = def.getKind().getValue() == StructureDefinitionKindCode.Value.RESOURCE;

    // FHIR uses "compiler magic" to set the type of id fields, but it has errors in several
    // versions of FHIR.  Here, assume all Resource IDs are type "id", and all DataType IDs are
    // type "string", which is the intention of the spec.
    StructureDefinition.Builder defBuilder = def.toBuilder();
    for (ElementDefinition.Builder elementBuilder :
        defBuilder.getSnapshotBuilder().getElementBuilderList()) {
      if (elementBuilder.getId().getValue().matches("[A-Za-z]*\\.id")) {
        for (int i = 0; i < elementBuilder.getTypeBuilder(0).getExtensionCount(); i++) {
          Extension.Builder extensionBuilder =
              elementBuilder.getTypeBuilder(0).getExtensionBuilder(i);
          if (extensionBuilder.getUrl().getValue().equals(FHIR_TYPE_EXTENSION_URL)) {
            extensionBuilder
                .getValueBuilder()
                .getUrlBuilder()
                .setValue(isResource ? "id" : "string");
          }
        }
      }
    }
    return defBuilder.build();
  }

  private List<SearchParameter> getSearchParameters(StructureDefinition def) {
    if (def.getKind().getValue() != StructureDefinitionKindCode.Value.RESOURCE) {
      // Not a resource - no search parameters to add.
      return new ArrayList<>();
    }
    String resourceTypeId = def.getSnapshot().getElementList().get(0).getId().getValue();
    // Get the string representation of the enum value for the resource type.
    try {
      EnumValueDescriptor enumValueDescriptor =
          Codes.codeStringToEnumValue(ResourceTypeCode.Value.getDescriptor(), resourceTypeId);
      return searchParameterMap.getOrDefault(
          ResourceTypeCode.Value.forNumber(enumValueDescriptor.getNumber()), new ArrayList<>());
    } catch (InvalidFhirException e) {
      throw new IllegalArgumentException(
          "Encountered unrecognized resource id: " + resourceTypeId, e);
    }
  }

  // Class for generating a single message from a single StructureDefinition.
  // This contains additional context from the containing ProtoGenerator, specific to the definition
  // it is the generator for.
  private final class PerDefinitionGenerator {
    private final StructureDefinition structureDefinition;
    private final ImmutableList<ElementDefinition> allElements;

    PerDefinitionGenerator(StructureDefinition structureDefinition) {
      this.structureDefinition = fixIdBug(structureDefinition);
      this.allElements =
          ImmutableList.copyOf(this.structureDefinition.getSnapshot().getElementList());
    }

    DescriptorProto generate() throws InvalidFhirException {
      DescriptorProto.Builder builder = DescriptorProto.newBuilder();
      builder.setOptions(generateOptions());
      generateMessage(allElements.get(0), builder);

      if (isProfile(structureDefinition)) {
        // Add all base structure definition url annotations
        StructureDefinition defInChain = structureDefinition;
        while (isProfile(defInChain)) {
          String baseUrl = defInChain.getBaseDefinition().getValue();
          builder.getOptionsBuilder().addExtension(Annotations.fhirProfileBase, baseUrl);
          defInChain = getDefinitionDataByUrl(baseUrl).structDef;
        }
      }
      return builder.build();
    }

    private MessageOptions generateOptions() {
      // Build a top-level message description.
      StringBuilder comment =
          new StringBuilder()
              .append("Auto-generated from StructureDefinition for ")
              .append(structureDefinition.getName().getValue());
      comment.append(".");
      if (structureDefinition.getSnapshot().getElement(0).hasShort()) {
        String shortString = structureDefinition.getSnapshot().getElement(0).getShort().getValue();
        if (!shortString.endsWith(".")) {
          shortString += ".";
        }
        comment.append("\n").append(shortString.replaceAll("[\\n\\r]", "\n"));
      }
      comment.append("\nSee ").append(structureDefinition.getUrl().getValue());

      // Add message-level annotations.
      MessageOptions.Builder optionsBuilder =
          MessageOptions.newBuilder()
              .setExtension(
                  Annotations.structureDefinitionKind,
                  Annotations.StructureDefinitionKindValue.valueOf(
                      "KIND_" + structureDefinition.getKind().getValue()))
              .setExtension(ProtoGeneratorAnnotations.messageDescription, comment.toString())
              .setExtension(
                  Annotations.fhirStructureDefinitionUrl, structureDefinition.getUrl().getValue());
      if (structureDefinition.getAbstract().getValue()) {
        optionsBuilder.setExtension(
            Annotations.isAbstractType, structureDefinition.getAbstract().getValue());
      }

      // Add search parameters
      List<Annotations.SearchParameter> searchParameterAnnotations = new ArrayList<>();
      for (SearchParameter searchParameter :
          ImmutableList.sortedCopyOf(
              (p1, p2) -> p1.getName().getValue().compareTo(p2.getName().getValue()),
              getSearchParameters(structureDefinition))) {
        searchParameterAnnotations.add(
            Annotations.SearchParameter.newBuilder()
                .setName(searchParameter.getName().getValue())
                .setType(
                    Annotations.SearchParameterType.forNumber(
                        searchParameter.getType().getValue().getNumber()))
                .setExpression(searchParameter.getExpression().getValue())
                .build());
      }
      if (!searchParameterAnnotations.isEmpty()) {
        optionsBuilder.setExtension(Annotations.searchParameter, searchParameterAnnotations);
      }
      return optionsBuilder.build();
    }

    private void makeClosedExtensionReservedField(
        FieldDescriptorProto.Builder field, ElementDefinition element, int tagNumber) {
      field
          .setNumber(tagNumber)
          .getOptionsBuilder()
          .setExtension(
              ProtoGeneratorAnnotations.reservedReason,
              "Field "
                  + tagNumber
                  + " reserved for unsliced field for element with closed slicing: "
                  + element.getId().getValue());
    }

    @CanIgnoreReturnValue
    private DescriptorProto generateMessage(
        ElementDefinition currentElement, DescriptorProto.Builder builder)
        throws InvalidFhirException {
      // Get the name of this message
      builder.setName(nameFromQualifiedName(getContainerType(currentElement)));

      // Add message-level FHIRPath constraints.
      ImmutableList<String> expressions = getFhirPathErrorConstraints(currentElement);
      if (!expressions.isEmpty()) {
        builder
            .getOptionsBuilder()
            .setExtension(Annotations.fhirPathMessageConstraint, expressions);
      }
      // Add warning constraints.
      ImmutableList<String> warnings = getFhirPathWarningConstraints(currentElement);
      if (!warnings.isEmpty()) {
        builder
            .getOptionsBuilder()
            .setExtension(Annotations.fhirPathMessageWarningConstraint, warnings);
      }

      // When generating a descriptor for a primitive type, the value part may already be present.
      int nextTag = builder.getFieldCount() + 1;

      // Some repeated fields can have profiled elements in them, that get inlined as fields.
      // The most common case of this is typed extensions.  We defer adding these to the end of the
      // message, so that non-profiled messages will be binary compatiple with this proto.
      // Note that the inverse is not true - loading a profiled message bytes into the non-profiled
      // will result in the data in the typed fields being dropped.
      List<ElementDefinition> deferredElements = new ArrayList<>();

      // Loop over the direct children of this element.
      for (ElementDefinition element : getDirectChildren(currentElement)) {
        if (element.getId().getValue().matches("[^.]*\\.value")
            && (element.getType(0).getCode().getValue().isEmpty()
                || element.getType(0).getCode().getValue().startsWith(FHIRPATH_TYPE_PREFIX))) {
          // This is a primitive value element.
          generatePrimitiveValue(element, builder);
          nextTag++;
          continue;
        }

        // Per spec, the fixed Extension.url on a top-level extension must match the
        // StructureDefinition url.  Since that is already added to the message via the
        // fhir_structure_definition_url, we can skip over it here.
        if (element.getBase().getPath().getValue().equals("Extension.url")
            && element.getFixed().hasUri()) {
          continue;
        }

        // Slices on choice types are handled during the creation of the choice type itself.
        // Ignore them here.
        if (GeneratorUtils.isChoiceTypeSlice(element)) {
          continue;
        }

        if (!isChoiceType(element) && !isSingleType(element)) {
          throw new IllegalArgumentException(
              "Illegal field has multiple types but is not a Choice Type:\n" + element);
        }

        if (isContainedResourceField(element)) {
          buildAndAddField(element, nextTag++, builder);
          builder
              .addFieldBuilder()
              .setNumber(nextTag)
              .getOptionsBuilder()
              .setExtension(
                  ProtoGeneratorAnnotations.reservedReason,
                  "Field "
                      + nextTag
                      + " reserved for strongly-typed ContainedResource for id: "
                      + element.getId().getValue());
          nextTag++;
        } else if (!isChoiceType(element)
            && element.getSlicing().getRules().getValue() == SlicingRulesCode.Value.CLOSED
            && element.getType(0).getCode().getValue().equals("Extension")) {
          // If this is an extension field that has closed slicing (i.e., all elements
          // should belong to a slice), don't generate an unsliced extension field, but reserve tag
          // number to avoid reassigning.
          makeClosedExtensionReservedField(builder.addFieldBuilder(), element, nextTag);
          nextTag++;
        } else {
          buildAndAddField(element, nextTag++, builder);
        }
      }

      for (ElementDefinition deferredElement : deferredElements) {
        // Currently we only support slicing for Extensions and Codings
        if (isElementSupportedForSlicing(deferredElement)
            || isContainedResourceField(deferredElement)) {
          buildAndAddField(deferredElement, nextTag++, builder);
        } else {
          builder
              .addFieldBuilder()
              .setNumber(nextTag)
              .getOptionsBuilder()
              .setExtension(
                  ProtoGeneratorAnnotations.reservedReason,
                  "field "
                      + nextTag
                      + " reserved for "
                      + deferredElement.getId().getValue()
                      + " which uses an unsupported slicing on "
                      + deferredElement.getType(0).getCode().getValue());
          nextTag++;
        }
      }
      return builder.build();
    }

    /** Generate the primitive value part of a datatype. */
    private void generatePrimitiveValue(
        ElementDefinition valueElement, DescriptorProto.Builder builder)
        throws InvalidFhirException {
      String defId = structureDefinition.getId().getValue();

      // If a regex for this primitive type is present, add it as a message-level annotation.
      if (valueElement.getTypeCount() == 1) {
        Optional<String> regexOptional = getPrimitiveRegex(valueElement);
        if (regexOptional.isPresent()) {
          builder.setOptions(
              builder.getOptions().toBuilder()
                  .setExtension(Annotations.valueRegex, regexOptional.get())
                  .build());
        }
      }

      // For historical reasons, primitive value fields appear first in primitive protos.
      // Therefore, we start numbering at one for these fields.
      // At the end of this function, we will shift all other fields down by the number of fields
      // we are adding.
      List<FieldDescriptorProto> fieldsToAdd = new ArrayList<>();
      if (TIME_LIKE_PRECISION_MAP.containsKey(defId)) {
        // Handle time-like types.
        EnumDescriptorProto.Builder enumBuilder = PRECISION_ENUM.toBuilder();
        for (String value : TIME_LIKE_PRECISION_MAP.get(defId)) {
          enumBuilder.addValue(
              EnumValueDescriptorProto.newBuilder()
                  .setName(value)
                  .setNumber(enumBuilder.getValueCount()));
        }
        builder.addEnumType(enumBuilder);
        FieldDescriptorProto.Builder valueField =
            FieldDescriptorProto.newBuilder()
                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setName("value_us")
                .setNumber(1);
        valueField
            .getOptionsBuilder()
            .setExtension(
                ProtoGeneratorAnnotations.fieldDescription, "Primitive value for " + defId);
        fieldsToAdd.add(valueField.build());
        if (TYPES_WITH_TIMEZONE.contains(defId)) {
          fieldsToAdd.add(TIMEZONE_FIELD);
        }
        fieldsToAdd.add(
            FieldDescriptorProto.newBuilder()
                .setName("precision")
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                .setTypeName(
                    "."
                        + protogenConfig.getProtoPackage()
                        + "."
                        + toFieldTypeCase(defId)
                        + ".Precision")
                .setNumber(TYPES_WITH_TIMEZONE.contains(defId) ? 3 : 2)
                .build());
      } else {
        // Handle non-time-like types by just adding the value field.
        FieldDescriptorProto.Builder valueField =
            FieldDescriptorProto.newBuilder()
                .setType(
                    PRIMITIVE_TYPE_OVERRIDES.getOrDefault(
                        defId, FieldDescriptorProto.Type.TYPE_STRING))
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setName("value")
                .setNumber(1);
        String description =
            valueElement.hasShort()
                ? valueElement.getShort().getValue()
                : "Primitive value for " + defId;
        valueField
            .getOptionsBuilder()
            .setExtension(ProtoGeneratorAnnotations.fieldDescription, description);
        if (isRequiredByFhir(valueElement)) {
          valueField
              .getOptionsBuilder()
              .setExtension(
                  Annotations.validationRequirement, Annotations.Requirement.REQUIRED_BY_FHIR);
        }
        fieldsToAdd.add(valueField.build());
      }
      // For historical reasons, the primitive value field (an correspoding timezone and precision
      // fields in the case of timelike primitives) are the first tag numbers added.  Therefore,
      // shift all pre-existing fields to be after those fields.
      int shiftSize = fieldsToAdd.size();
      for (FieldDescriptorProto oldField : builder.getFieldList()) {
        fieldsToAdd.add(oldField.toBuilder().setNumber(oldField.getNumber() + shiftSize).build());
      }
      builder.clearField().addAllField(fieldsToAdd);
    }

    private void buildAndAddField(
        ElementDefinition element, int tag, DescriptorProto.Builder builder)
        throws InvalidFhirException {
      // Generate the field. If this field doesn't actually exist in this version of the
      // message, for example, the max attribute is 0, buildField returns null and no field
      // should be added.
      Optional<FieldDescriptorProto> fieldOptional = buildField(element, tag);
      if (fieldOptional.isPresent()) {
        FieldDescriptorProto field = fieldOptional.get();
        Optional<DescriptorProto> optionalNestedType = buildNestedTypeIfNeeded(element);
        if (optionalNestedType.isPresent()) {
          builder.addNestedType(optionalNestedType.get());
        } else {
          // There is no submessage defined for this field, so apply constraints to the field
          // itself.
          ImmutableList<String> expressions = getFhirPathErrorConstraints(element);
          if (!expressions.isEmpty()) {
            field =
                field.toBuilder()
                    .setOptions(
                        field.getOptions().toBuilder()
                            .setExtension(Annotations.fhirPathConstraint, expressions))
                    .build();
          }
          // Add warning constraints.
          ImmutableList<String> warnings = getFhirPathWarningConstraints(element);
          if (!warnings.isEmpty()) {
            field =
                field.toBuilder()
                    .setOptions(
                        field.getOptions().toBuilder()
                            .setExtension(Annotations.fhirPathWarningConstraint, warnings))
                    .build();
          }
        }

        builder.addField(field);
      } else if (!element.getPath().getValue().equals("Extension.extension")
          && !element.getPath().getValue().equals("Extension.value[x]")) {
        // Don't bother adding reserved messages for Extension.extension or Extension.value[x]
        // since that's part of the extension definition, and adds a lot of unhelpful noise.
        builder
            .addFieldBuilder()
            .setNumber(tag)
            .getOptionsBuilder()
            .setExtension(
                ProtoGeneratorAnnotations.reservedReason,
                element.getPath().getValue() + " not present on profile.");
      }
    }

    private Optional<DescriptorProto> buildNestedTypeIfNeeded(ElementDefinition element)
        throws InvalidFhirException {
      Optional<DescriptorProto> choiceType = buildChoiceTypeIfRequired(element);
      return choiceType.isPresent() ? choiceType : buildNonChoiceTypeNestedTypeIfNeeded(element);
    }

    private Optional<DescriptorProto> buildNonChoiceTypeNestedTypeIfNeeded(
        ElementDefinition element) throws InvalidFhirException {
      if (element.getTypeCount() != 1) {
        return Optional.empty();
      }

      Optional<DescriptorProto> profiledCode = makeBoundCodeIfRequired(element);
      if (profiledCode.isPresent()) {
        return profiledCode;
      }

      // If this is a container type, extension, define the inner message.
      if (isContainer(element)) {
        return Optional.of(generateMessage(element, DescriptorProto.newBuilder()));
      }
      return Optional.empty();
    }

    // Generates the nested type descriptor proto for a choice type if required.
    private Optional<DescriptorProto> buildChoiceTypeIfRequired(ElementDefinition element)
        throws InvalidFhirException {
      Optional<ElementDefinition> choiceTypeBase = getChoiceTypeBase(element);
      if (choiceTypeBase.isPresent()) {
        List<ElementDefinition.TypeRef> baseTypes = choiceTypeBase.get().getTypeList();
        Map<String, Integer> baseTypesToIndex = new HashMap<>();
        for (int i = 0; i < baseTypes.size(); i++) {
          String code = baseTypes.get(i).getCode().getValue();
          // Only add each code type once.  This is only relevant for references, which can appear
          // multiple times.
          baseTypesToIndex.putIfAbsent(code, i);
        }
        DescriptorProto baseChoiceType = makeChoiceType(choiceTypeBase.get());
        final Set<String> uniqueTypes = new HashSet<>();
        ImmutableList<FieldDescriptorProto> matchingFields =
            element.getTypeList().stream()
                .filter(type -> uniqueTypes.add(type.getCode().getValue()))
                .map(
                    type ->
                        baseChoiceType.getField(baseTypesToIndex.get(type.getCode().getValue())))
                .collect(toImmutableList());

        // TODO(b/244184211): If a choice type is a slice of another choice type (not a pure
        // constraint, but actual slice) we'll need to update the name and type name as well.
        DescriptorProto.Builder newChoiceType =
            baseChoiceType.toBuilder().clearField().addAllField(matchingFields);

        // Constraints may be on the choice base element rather than the value element,
        // so reflect that here.
        ImmutableList<String> expressions = getFhirPathErrorConstraints(element);
        if (!expressions.isEmpty()) {
          newChoiceType.setOptions(
              baseChoiceType.getOptions().toBuilder()
                  .setExtension(Annotations.fhirPathMessageConstraint, expressions));
        }
        // Add warning constraints.
        ImmutableList<String> warnings = getFhirPathWarningConstraints(element);
        if (!warnings.isEmpty()) {
          newChoiceType.setOptions(
              baseChoiceType.getOptions().toBuilder()
                  .setExtension(Annotations.fhirPathMessageWarningConstraint, warnings));
        }

        return Optional.of(newChoiceType.build());
      }

      if (isChoiceType(element)) {
        return Optional.of(makeChoiceType(element));
      }

      return Optional.empty();
    }

    private Optional<DescriptorProto> makeBoundCodeIfRequired(ElementDefinition element)
        throws InvalidFhirException {
      if (element.getTypeCount() != 1 || !element.getType(0).getCode().getValue().equals("code")) {
        return Optional.empty();
      }

      Optional<String> boundValueSetUrl = getBindingValueSetUrl(element);
      if (!boundValueSetUrl.isPresent()) {
        return Optional.empty();
      }

      Optional<QualifiedType> typeWithBoundValueSet = checkForTypeWithBoundValueSet(element);
      if (!typeWithBoundValueSet.isPresent()) {
        return Optional.empty();
      }

      return Optional.of(
          boundCodeGenerator.generateCodeBoundToValueSet(
              typeWithBoundValueSet.get().getName(), boundValueSetUrl.get()));
    }

    private Optional<QualifiedType> checkForTypeWithBoundValueSet(ElementDefinition element)
        throws InvalidFhirException {
      if (getDistinctTypeCount(element) == 1) {
        String containerName = getContainerType(element);
        ElementDefinition.TypeRef type = element.getType(0);

        String typeName = type.getCode().getValue();
        Optional<String> valueSetUrl = getBindingValueSetUrl(element);
        if (valueSetUrl.isPresent()) {
          if (typeName.equals("code")) {
            if (!containerName.endsWith("Code") && !containerName.endsWith(".CodeType")) {
              // Carve out some exceptions because CodeCode and CodeTypeCode sounds silly.
              containerName = containerName + "Code";
            }
            return Optional.of(new QualifiedType(containerName, protogenConfig.getProtoPackage()));
          }
        }
      }
      return Optional.empty();
    }

    /** Extract the type of a container field, possibly by reference. */
    private String getContainerType(ElementDefinition element) throws InvalidFhirException {
      if (element.hasContentReference()) {
        // Find the named element which was referenced. We'll use the type of that element.
        // Strip the first character from the content reference since it is a '#'
        String referencedElementId = element.getContentReference().getValue().substring(1);
        ElementDefinition referencedElement = getElementById(referencedElementId);
        if (!isContainer(referencedElement)) {
          throw new IllegalArgumentException(
              "ContentReference does not reference a container: " + element.getContentReference());
        }
        if (lastIdToken(referencedElementId).slicename != null
            && !isElementSupportedForSlicing(referencedElement)) {
          // This is a reference to a slice of a field, but the slice isn't a supported slice type.
          // Just use a reference to the base field.

          // TODO(b/244184211):  This logic assumes only a single level of slicing is present - the
          // base element could theoretically also be unsupported for slicing.
          referencedElement =
              getElementById(
                  referencedElementId.substring(0, referencedElementId.lastIndexOf(":")));
        }
        return getContainerType(referencedElement);
      }

      // The container type is the full type of the message that will be generated (minus package).
      // It is derived from the id (e.g., Medication.package.content), and these are usually equal
      // other than casing (e.g., Medication.Package.Content).
      // However, any parent in the path could have been renamed via a explicit type name
      // extensions.

      // Check for explicit renamings on this element.
      List<Message> explicitTypeNames =
          Extensions.getExtensionsWithUrl(EXPLICIT_TYPE_NAME_EXTENSION_URL, element);

      if (explicitTypeNames.size() > 1) {
        throw new InvalidFhirException(
            "Element has multiple explicit type names: " + element.getId().getValue());
      }

      // Use explicit type name if present.  Otherwise, use the field_name, converted to FieldType
      // casing, as the submessage name.
      String typeName =
          toFieldTypeCase(
              explicitTypeNames.isEmpty()
                  ? getNameForElement(element)
                  : (String)
                      Extensions.getExtensionValue(explicitTypeNames.get(0), "string_value"));
      if (isChoiceType(element)) {
        typeName = typeName + "X";
      }

      Optional<ElementDefinition> parentOpt = GeneratorUtils.getParent(element, allElements);

      String packageString;
      if (parentOpt.isPresent()) {
        ElementDefinition parent = parentOpt.get();
        String parentType = getContainerType(parent);
        packageString = parentType + ".";
      } else {
        packageString = "";
      }

      if (packageString.startsWith(typeName + ".")
          || packageString.contains("." + typeName + ".")
          || (typeName.equals("Code") && parentOpt.isPresent())) {
        typeName = typeName + "Type";
      }
      return packageString + typeName;
    }

    // Returns the only element in the list matching a given id.
    // Throws IllegalArgumentException if zero or more than one matching element is found.
    private ElementDefinition getElementById(String id) throws InvalidFhirException {
      return GeneratorUtils.getElementById(id, allElements);
    }

    /**
     * Gets the field type and package of a potentially complex element. This handles choice types,
     * types that reference other elements, references, profiles, etc.
     */
    private QualifiedType getQualifiedFieldType(ElementDefinition element)
        throws InvalidFhirException {
      Optional<QualifiedType> valueSetType = checkForTypeWithBoundValueSet(element);
      if (valueSetType.isPresent()) {
        return valueSetType.get();
      }

      if (isContainer(element) || isChoiceType(element)) {
        return new QualifiedType(getContainerType(element), protogenConfig.getProtoPackage());
      } else if (element.hasContentReference()) {
        // Get the type for this container from a named reference to another element.
        return new QualifiedType(getContainerType(element), protogenConfig.getProtoPackage());
      } else if (element.getType(0).getCode().getValue().equals("Reference")) {
        return new QualifiedType(
            USE_TYPED_REFERENCES ? getTypedReferenceName(element.getTypeList()) : "Reference",
            protogenConfig.getProtoPackage());
      } else {
        if (element.getTypeCount() > 1) {
          throw new IllegalArgumentException(
              "Unknown multiple type definition on element: " + element.getId());
        }
        // Note: this is the "fhir type", e.g., Resource, BackboneElement, boolean,
        // not the field type name.
        String normalizedFhirTypeName =
            normalizeType(Iterables.getOnlyElement(element.getTypeList()));

        // See https://jira.hl7.org/browse/FHIR-25262
        if (element.getId().getValue().equals("xhtml.id")) {
          normalizedFhirTypeName = "String";
        }

        if (normalizedFhirTypeName.equals("Resource")) {
          // We represent "Resource" FHIR types as "Any",
          // unless we are on the Bundle type, in which case we use "ContainedResources" type.
          // This allows defining resources in separate files without circular dependencies.
          if (allElements.get(0).getId().getValue().equals("Bundle")) {
            return new QualifiedType("ContainedResource", protogenConfig.getProtoPackage());
          } else {
            return new QualifiedType("Any", "google.protobuf");
          }
        }
        return new QualifiedType(normalizedFhirTypeName, protogenConfig.getProtoPackage());
      }
    }

    private ImmutableList<ElementDefinition> getDescendants(ElementDefinition element) {
      // The id of descendants should start with the parent id + at least one more token.
      String parentIdPrefix = element.getId().getValue() + ".";
      return allElements.stream()
          .filter(
              candidateElement -> candidateElement.getId().getValue().startsWith(parentIdPrefix))
          .collect(toImmutableList());
    }

    private ImmutableList<ElementDefinition> getDirectChildren(ElementDefinition element) {
      List<String> messagePathParts = Splitter.on('.').splitToList(element.getId().getValue());
      return getDescendants(element).stream()
          .filter(
              candidateElement -> {
                List<String> parts =
                    Splitter.on('.').splitToList(candidateElement.getId().getValue());
                // To be a direct child, the id should start with the parent id, and add a single
                // additional token.
                return parts.size() == messagePathParts.size() + 1;
              })
          .collect(toImmutableList());
    }

    private Optional<String> getBindingValueSetUrl(ElementDefinition element) {
      if (element.getBinding().getStrength().getValue() != BindingStrengthCode.Value.REQUIRED) {
        return Optional.empty();
      }
      String url = GeneratorUtils.getCanonicalUri(element.getBinding().getValueSet());
      if (url.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(url);
    }

    /** Returns the field name that should be used for an element. */
    private String getNameForElement(ElementDefinition element) {
      if (element
          .getId()
          .getValue()
          .equals(structureDefinition.getSnapshot().getElement(0).getId().getValue())) {
        return GeneratorUtils.getTypeName(structureDefinition);
      }

      return hyphenToCamel(lastIdToken(element).pathpart);
    }

    private long getDistinctTypeCount(ElementDefinition element) {
      // Don't do fancier logic if fast logic is sufficient.
      if (element.getTypeCount() < 2 || USE_TYPED_REFERENCES) {
        return element.getTypeCount();
      }
      return element.getTypeList().stream().map(type -> type.getCode()).distinct().count();
    }

    /** Build a single field for the proto. */
    private Optional<FieldDescriptorProto> buildField(ElementDefinition element, int nextTag)
        throws InvalidFhirException {
      FieldDescriptorProto.Label fieldSize = getFieldSize(element);
      if (fieldSize == null) {
        // This field has a max size of zero.  Do not emit a field.
        return Optional.empty();
      }

      FieldOptions.Builder options = FieldOptions.newBuilder();

      // Add a short description of the field.
      if (element.hasShort()) {
        options.setExtension(
            ProtoGeneratorAnnotations.fieldDescription, element.getShort().getValue());
      }

      if (isRequiredByFhir(element)) {
        options.setExtension(
            Annotations.validationRequirement, Annotations.Requirement.REQUIRED_BY_FHIR);
      } else if (element.getMin().getValue() != 0) {
        System.out.println("Unexpected minimum field count: " + element.getMin().getValue());
      }

      Optional<ElementDefinition> choiceTypeBase = getChoiceTypeBase(element);
      if (choiceTypeBase.isPresent()) {
        ElementDefinition choiceTypeBaseElement = choiceTypeBase.get();
        String baseName = getNameForElement(choiceTypeBaseElement);
        String baseContainerType = getContainerType(choiceTypeBaseElement);
        String containerType = getContainerType(element);
        containerType =
            containerType.substring(0, containerType.lastIndexOf(".") + 1)
                + baseContainerType.substring(baseContainerType.lastIndexOf(".") + 1);

        return Optional.of(
            buildFieldInternal(
                    baseName,
                    containerType,
                    protogenConfig.getProtoPackage(),
                    nextTag,
                    fieldSize,
                    options.build())
                .build());
      }

      // Add typed reference options
      if (!isChoiceType(element)
          && element.getTypeCount() > 0
          && element.getType(0).getCode().getValue().equals("Reference")) {
        for (ElementDefinition.TypeRef type : element.getTypeList()) {
          if (type.getCode().getValue().equals("Reference") && type.getTargetProfileCount() > 0) {
            for (Canonical referenceType : type.getTargetProfileList()) {
              if (!referenceType.getValue().isEmpty()) {
                addReferenceType(options, referenceType.getValue());
              }
            }
          }
        }
      }

      QualifiedType fieldType = getQualifiedFieldType(element);
      FieldDescriptorProto.Builder fieldBuilder =
          buildFieldInternal(
              getNameForElement(element),
              fieldType.type,
              fieldType.packageName,
              nextTag,
              fieldSize,
              options.build());
      if (isExtensionBackboneElement(element)) {
        // For internal extension, the default is to assume the url is equal to the jsonName of the
        // field. The json name of the field is the snake-to-json fieldName, unless a jsonName was
        // explicitly set.
        String url =
            getElementById(element.getId().getValue() + ".url").getFixed().getUri().getValue();
        if (fieldBuilder.hasJsonName()
            ? !fieldBuilder.getJsonName().equals(url)
            : !snakeCaseToJsonCase(fieldBuilder.getName()).equals(url)) {
          fieldBuilder.getOptionsBuilder().setExtension(Annotations.fhirInlinedExtensionUrl, url);
        }
      }
      return Optional.of(fieldBuilder.build());
    }

    /** Add a choice type container message to the proto. */
    private DescriptorProto makeChoiceType(ElementDefinition element) throws InvalidFhirException {
      QualifiedType choiceQualifiedType = getQualifiedFieldType(element);
      DescriptorProto.Builder choiceType =
          DescriptorProto.newBuilder().setName(choiceQualifiedType.getName());
      choiceType.getOptionsBuilder().setExtension(Annotations.isChoiceType, true);

      // Add error constraints on choice types.
      ImmutableList<String> expressions = getFhirPathErrorConstraints(element);
      if (!expressions.isEmpty()) {
        choiceType
            .getOptionsBuilder()
            .setExtension(Annotations.fhirPathMessageConstraint, expressions);
      }
      // Add warning constraints.
      ImmutableList<String> warnings = getFhirPathWarningConstraints(element);
      if (!warnings.isEmpty()) {
        choiceType
            .getOptionsBuilder()
            .setExtension(Annotations.fhirPathMessageWarningConstraint, warnings);
      }

      choiceType.addOneofDeclBuilder().setName("choice");

      int nextTag = 1;
      // Group types.
      List<ElementDefinition.TypeRef> types = new ArrayList<>();
      List<String> referenceTypes = new ArrayList<>();
      Set<String> foundTypes = new HashSet<>();
      for (ElementDefinition.TypeRef type : element.getTypeList()) {
        if (!foundTypes.contains(type.getCode().getValue())) {
          types.add(type);
          foundTypes.add(type.getCode().getValue());
        }

        if (type.getCode().getValue().equals("Reference") && type.getTargetProfileCount() > 0) {
          for (Canonical referenceType : type.getTargetProfileList()) {
            if (!referenceType.getValue().isEmpty()) {
              referenceTypes.add(referenceType.getValue());
            }
          }
        }
      }

      for (ElementDefinition.TypeRef type : types) {
        String fieldName =
            Ascii.toLowerCase(type.getCode().getValue().substring(0, 1))
                + type.getCode().getValue().substring(1);

        // There are two cases of choice type fields:
        // a) we need to generate a custom type for the field (e.g., a bound code, or a profiled
        // type)
        // b) it's an already existing type, so just generate the field.

        // Check for a slice of this type on the choice, e.g., value[x]:valueCode
        // If one is found, use that to create the choice field and type.  Otherwise, just use the
        // value[x] element itself.
        Optional<DescriptorProto> typeFromChoiceElement =
            buildNonChoiceTypeNestedTypeIfNeeded(element);

        if (typeFromChoiceElement.isPresent()) {
          // There is a sub type
          choiceType.addNestedType(typeFromChoiceElement.get());
          QualifiedType sliceType =
              choiceQualifiedType.childType(typeFromChoiceElement.get().getName());
          FieldDescriptorProto.Builder fieldBuilder =
              buildFieldInternal(
                      fieldName,
                      sliceType.type,
                      sliceType.packageName,
                      nextTag++,
                      FieldDescriptorProto.Label.LABEL_OPTIONAL,
                      FieldOptions.getDefaultInstance())
                  .setOneofIndex(0);
          choiceType.addField(fieldBuilder);
        } else {
          // If no custom type was generated, just use the type name from the core FHIR types.
          // TODO(b/244184211):  This assumes all types in a oneof are core FHIR types.  In order to
          // support custom types, we'll need to load the structure definition for the type and
          // check
          // against knownStructureDefinitionPackages
          FieldOptions.Builder options = FieldOptions.newBuilder();
          if (fieldName.equals("reference")) {
            for (String referenceType : referenceTypes) {
              addReferenceType(options, referenceType);
            }
          }
          FieldDescriptorProto.Builder fieldBuilder =
              buildFieldInternal(
                      fieldName,
                      normalizeType(type),
                      protogenConfig.getProtoPackage(),
                      nextTag++,
                      FieldDescriptorProto.Label.LABEL_OPTIONAL,
                      options.build())
                  .setOneofIndex(0);
          choiceType.addField(fieldBuilder);
        }
      }
      return choiceType.build();
    }

    private boolean isRequiredByFhir(ElementDefinition element) {
      return element.getMin().getValue() == 1;
    }
  }

  private static final ImmutableSet<String> DATATYPES_TO_SKIP =
      ImmutableSet.of(
          "http://hl7.org/fhir/StructureDefinition/Reference",
          "http://hl7.org/fhir/StructureDefinition/Element",
          "http://hl7.org/fhir/StructureDefinition/elementdefinition-de",
          "http://hl7.org/fhir/StructureDefinition/Extension");

  public FileDescriptorProto generateDatatypesFileDescriptor() throws InvalidFhirException {
    return generateFileDescriptor(
            stream(inputPackage.structureDefinitions())
                .filter(
                    def ->
                        (def.getKind().getValue()
                                    == StructureDefinitionKindCode.Value.PRIMITIVE_TYPE
                                || def.getKind().getValue()
                                    == StructureDefinitionKindCode.Value.COMPLEX_TYPE)
                            && !DATATYPES_TO_SKIP.contains(def.getUrl().getValue())
                            && !def.getBaseDefinition()
                                .getValue()
                                .equals("http://hl7.org/fhir/StructureDefinition/Extension"))
                .collect(toImmutableList()))
        .build();
  }

  public FileDescriptorProto generateResourceFileDescriptor(StructureDefinition def)
      throws InvalidFhirException {
    return generateFileDescriptor(ImmutableList.of(def))
        .addDependency(protogenConfig.getSourceDirectory() + "/datatypes.proto")
        .addDependency("google/protobuf/any.proto")
        .build();
  }

  private FileDescriptorProto.Builder generateFileDescriptor(List<StructureDefinition> defs)
      throws InvalidFhirException {
    FileDescriptorProto.Builder builder = FileDescriptorProto.newBuilder();
    builder.setPackage(protogenConfig.getProtoPackage()).setSyntax("proto3");
    FileOptions.Builder options = FileOptions.newBuilder();
    if (!protogenConfig.getJavaProtoPackage().isEmpty()) {
      options.setJavaPackage(protogenConfig.getJavaProtoPackage()).setJavaMultipleFiles(true);
    }
    if (!protogenConfig.getGoProtoPackage().isEmpty()) {
      options.setGoPackage(protogenConfig.getGoProtoPackage());
    }

    // TODO(b/267772954292116008): Don't rely on precompiled version enum.
    options.setExtension(Annotations.fhirVersion, protogenConfig.getFhirVersion());

    builder.setOptions(options);
    for (StructureDefinition def : defs) {
      builder.addMessageType(generateProto(def));
    }

    builder.addDependency(new File(GeneratorUtils.ANNOTATION_PATH, "annotations.proto").toString());
    builder.addDependency(
        new File(protogenConfig.getSourceDirectory() + "/codes.proto").toString());
    builder.addDependency(
        new File(protogenConfig.getSourceDirectory() + "/valuesets.proto").toString());

    return builder;
  }

  private static boolean dependsOnTypes(DescriptorProto descriptor, ImmutableSet<String> types) {

    for (FieldDescriptorProto field : descriptor.getFieldList()) {
      if (types.contains(field.getTypeName())) {
        return true;
      }
    }
    for (DescriptorProto nested : descriptor.getNestedTypeList()) {
      if (dependsOnTypes(nested, types)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a version of the passed-in FileDescriptor that contains a ContainedResource message,
   * which contains only the resource types present in the generated FileDescriptorProto.
   */
  public FileDescriptorProto addContainedResource(
      FileDescriptorProto fileDescriptor, List<DescriptorProto> resourceTypes) {
    DescriptorProto.Builder contained =
        DescriptorProto.newBuilder()
            .setName("ContainedResource")
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("oneof_resource"));
    // When generating contained resources for the core type (resources.proto),
    // iterate through all the non-abstract resources sorted alphabetically, assigning tag numbers
    // as you go
    TreeSet<DescriptorProto> sortedResources =
        new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
    sortedResources.addAll(resourceTypes);
    int tagNumber = 1;
    for (DescriptorProto type : sortedResources) {
      if (AnnotationUtils.isResource(type)
          && !type.getOptions().getExtension(Annotations.isAbstractType)) {
        contained.addField(
            FieldDescriptorProto.newBuilder()
                .setName(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, type.getName()))
                .setNumber(tagNumber++)
                .setTypeName(type.getName())
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setOneofIndex(0 /* the oneof_resource */)
                .build());
      }
    }

    return fileDescriptor.toBuilder().addMessageType(contained).build();
  }

  private static Optional<String> getPrimitiveRegex(ElementDefinition element)
      throws InvalidFhirException {
    List<Message> regexExtensions =
        Extensions.getExtensionsWithUrl(REGEX_EXTENSION_URL, element.getType(0));
    if (regexExtensions.isEmpty()) {
      return Optional.empty();
    }
    if (regexExtensions.size() > 1) {
      throw new InvalidFhirException(
          "Multiple regex extensions found on " + element.getId().getValue());
    }
    return Optional.of(
        (String) Extensions.getExtensionValue(regexExtensions.get(0), "string_value"));
  }

  /**
   * Fields of the abstract types Element or BackboneElement are containers, which contain internal
   * fields (including possibly nested containers). Also, the top-level message is a container. See
   * https://www.hl7.org/fhir/backboneelement.html for additional information.
   */
  private static boolean isContainer(ElementDefinition element) {
    if (element.getTypeCount() != 1) {
      return false;
    }
    if (element.getId().getValue().indexOf('.') == -1) {
      return true;
    }
    String type = element.getType(0).getCode().getValue();
    return type.equals("BackboneElement") || type.equals("Element");
  }

  /**
   * Does this element have a single, well-defined type? For example: string, Patient or
   * Observation.ReferenceRange, as opposed to one of a set of types, most commonly encoded in field
   * with names like "value[x]".
   */
  private static boolean isSingleType(ElementDefinition element) {
    // If the type of this element is defined by referencing another element,
    // then it has a single defined type.
    if (element.getTypeCount() == 0 && element.hasContentReference()) {
      return true;
    }

    // Loop through the list of types. There may be multiple copies of the same
    // high-level type, for example, multiple kinds of References.
    Set<String> types = new HashSet<>();
    for (ElementDefinition.TypeRef type : element.getTypeList()) {
      if (type.hasCode()) {
        types.add(type.getCode().getValue());
      }
    }
    return types.size() == 1;
  }

  private static boolean isChoiceType(ElementDefinition element) {
    return lastIdToken(element).isChoiceType;
  }

  // Returns the FHIRPath Error constraints on the given element, if any.
  private static ImmutableList<String> getFhirPathErrorConstraints(ElementDefinition element) {
    return element.getConstraintList().stream()
        .filter(ElementDefinition.Constraint::hasExpression)
        .filter(
            constraint -> constraint.getSeverity().getValue() == ConstraintSeverityCode.Value.ERROR)
        .map(constraint -> constraint.getExpression().getValue())
        .filter(expression -> !EXCLUDED_FHIR_CONSTRAINTS.contains(expression))
        .collect(toImmutableList());
  }

  // Returns the FHIRPath Warning constraints on the given element, if any.
  private static ImmutableList<String> getFhirPathWarningConstraints(ElementDefinition element) {
    return element.getConstraintList().stream()
        .filter(ElementDefinition.Constraint::hasExpression)
        .filter(
            constraint ->
                constraint.getSeverity().getValue() == ConstraintSeverityCode.Value.WARNING)
        .map(constraint -> constraint.getExpression().getValue())
        .filter(expression -> !EXCLUDED_FHIR_CONSTRAINTS.contains(expression))
        .collect(toImmutableList());
  }

  private static FieldDescriptorProto.@Nullable Label getFieldSize(ElementDefinition element) {
    if (element.getMax().getValue().equals("0")) {
      // This field doesn't actually exist.
      return null;
    }
    return element.getMax().getValue().equals("1")
        ? FieldDescriptorProto.Label.LABEL_OPTIONAL
        : FieldDescriptorProto.Label.LABEL_REPEATED;
  }

  private static String hyphenToCamel(String fieldName) {
    int i;
    while ((i = fieldName.indexOf("-")) != -1) {
      fieldName =
          fieldName.substring(0, i)
              + Ascii.toUpperCase(fieldName.substring(i + 1, i + 2))
              + fieldName.substring(i + 2);
    }
    return fieldName;
  }

  // TODO(b/244184211): memoize
  private Optional<ElementDefinition> getChoiceTypeBase(ElementDefinition element)
      throws InvalidFhirException {
    if (!element.hasBase()) {
      return Optional.empty();
    }
    String basePath = element.getBase().getPath().getValue();
    if (basePath.equals("Extension.value[x]")) {
      // Extension value fields extend from choice-types, but since single-typed extensions will be
      // inlined as that type anyway, there's no point in generating a choice type for them.
      return Optional.empty();
    }
    String baseType = Splitter.on(".").splitToList(basePath).get(0);
    if (basePath.endsWith("[x]")) {
      ElementDefinition choiceTypeBase =
          getElementById(basePath, baseStructDefsById.get(baseType).getSnapshot().getElementList());
      return Optional.of(choiceTypeBase);
    }
    if (!baseType.equals("Element")) {
      // Traverse up the tree to check for a choice type in this element's ancestry.
      if (!baseStructDefsById.containsKey(baseType)) {
        throw new IllegalArgumentException("Unknown StructureDefinition id: " + baseType);
      }
      ElementDefinition baseElement =
          getElementById(basePath, baseStructDefsById.get(baseType).getSnapshot().getElementList());
      if (baseElement.getId().equals(element.getId())) {
        // Starting with r4, elements that don't inherit from anything list themselves as base :/
        return Optional.empty();
      }
      return getChoiceTypeBase(baseElement);
    }
    return Optional.empty();
  }

  private void addReferenceType(FieldOptions.Builder options, String referenceUrl) {
    options.addExtension(
        Annotations.validReferenceType, getBaseStructureDefinitionData(referenceUrl).inlineType);
  }

  /**
   * Given a structure definition url, returns the base (FHIR) structure definition data for that
   * type.
   */
  private StructureDefinitionData getBaseStructureDefinitionData(String url) {
    StructureDefinitionData defData = getDefinitionDataByUrl(url);
    while (isProfile(defData.structDef)) {
      defData = getDefinitionDataByUrl(defData.structDef.getBaseDefinition().getValue());
    }
    return defData;
  }

  private static FieldDescriptorProto.Builder buildFieldInternal(
      String fhirName,
      String fieldType,
      String fieldPackage,
      int tag,
      FieldDescriptorProto.Label size,
      FieldOptions options) {
    FieldDescriptorProto.Builder builder =
        FieldDescriptorProto.newBuilder()
            .setNumber(tag)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setLabel(size)
            .setTypeName("." + fieldPackage + "." + fieldType);

    String fieldName =
        RESERVED_FIELD_NAMES.contains(fhirName)
            ? toFieldNameCase(fhirName) + "_value"
            : toFieldNameCase(fhirName);

    builder.setName(fieldName);
    if (!fhirName.equals(snakeCaseToJsonCase(fieldName))) {
      // We can't recover the original FHIR field name with a to-json transform, so add an
      // annotation
      // for the original field name.
      builder.setJsonName(fhirName);
    }

    // Add annotations.
    if (!options.equals(FieldOptions.getDefaultInstance())) {
      builder.setOptions(options);
    }
    return builder;
  }

  private String normalizeType(ElementDefinition.TypeRef type) {
    String code = type.getCode().getValue();
    if (code.startsWith(FHIRPATH_TYPE_PREFIX)) {
      for (Extension extension : type.getExtensionList()) {
        if (extension.getUrl().getValue().equals(FHIR_TYPE_EXTENSION_URL)) {
          return toFieldTypeCase(extension.getValue().getUrl().getValue());
        }
      }
    }
    if (type.getProfileCount() == 0 || type.getProfile(0).getValue().isEmpty()) {
      return toFieldTypeCase(code);
    }
    String profileUrl = type.getProfile(0).getValue();
    if (structDefDataByUrl.containsKey(profileUrl)) {
      return structDefDataByUrl.get(profileUrl).inlineType;
    }
    throw new IllegalArgumentException(
        "Unable to deduce typename for profile: " + profileUrl + " on " + type);
  }

  private static String snakeCaseToJsonCase(String snakeString) {
    return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, snakeString);
  }

  private static boolean isContainedResourceField(ElementDefinition element) {
    return element.getBase().getPath().getValue().equals("DomainResource.contained");
  }

  private String getTypedReferenceName(List<ElementDefinition.TypeRef> typeList) {
    // Use a Tree Set to have a stable sort.
    TreeSet<String> refTypes =
        typeList.stream()
            .flatMap(type -> type.getTargetProfileList().stream())
            .map(Canonical::getValue)
            .collect(toCollection(TreeSet::new));

    String refType = null;
    for (String r : refTypes) {
      if (!r.isEmpty()) {
        if (structDefDataByUrl.containsKey(r)) {
          r = structDefDataByUrl.get(r).inlineType;
        } else {
          throw new IllegalArgumentException("Unsupported reference profile: " + r);
        }
        if (refType == null) {
          refType = r;
        } else {
          refType = refType + "Or" + r;
        }
      }
    }
    if (refType != null && !refType.equals("Resource")) {
      // Specialize the reference type.
      return refType + "Reference";
    }
    return "Reference";
  }

  private static boolean isExtensionBackboneElement(ElementDefinition element) {
    // An element is an extension element if either
    // A) it is a root element with id "Extension" and is a derivation from a base element or
    // B) it is a slice on an extension that is not defined by an external profile.
    if (!element.hasBase()) {
      return false;
    }
    String idString = element.getId().getValue();
    boolean isRootExtensionElement =
        idString.equals("Extension")
            || (!idString.contains(".") && idString.startsWith("Extension:"));
    boolean isInternallyDefinedExtension =
        (element.getBase().getPath().getValue().endsWith(".extension")
            && lastIdToken(element).slicename != null
            && element.getType(0).getProfileCount() == 0);
    return isRootExtensionElement || isInternallyDefinedExtension;
  }

  // TODO(b/139489684): consider supporting more types of slicing.
  private static boolean isElementSupportedForSlicing(ElementDefinition element) {
    return element.getTypeCount() == 1
        && (element.getType(0).getCode().getValue().equals("Extension")
            || element.getType(0).getCode().getValue().equals("Coding"));
  }
}