/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.invocationbuilders.passes;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.template.soy.shared.internal.gencode.JavaGenerationUtils.appendImmutableSetInline;
import static com.google.template.soy.shared.internal.gencode.JavaGenerationUtils.appendJavadoc;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IndentedLinesBuilder;
import com.google.template.soy.invocationbuilders.javatypes.FutureJavaType;
import com.google.template.soy.invocationbuilders.javatypes.JavaType;
import com.google.template.soy.invocationbuilders.javatypes.ProtoEnumJavaType;
import com.google.template.soy.invocationbuilders.javatypes.ProtoJavaType;
import com.google.template.soy.invocationbuilders.passes.SoyFileNodeTransformer.FileInfo;
import com.google.template.soy.invocationbuilders.passes.SoyFileNodeTransformer.ParamInfo;
import com.google.template.soy.invocationbuilders.passes.SoyFileNodeTransformer.ParamStatus;
import com.google.template.soy.invocationbuilders.passes.SoyFileNodeTransformer.TemplateInfo;
import com.google.template.soy.shared.internal.gencode.GeneratedFile;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.TemplateMetadata.Parameter;
import com.google.template.soy.soytree.TemplateRegistry;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Visitor for generating Java template parameter builders (see {@link
 * com.google.template.soy.data.BaseSoyTemplateImpl}) that can be used for invoking Soy templates
 * from Java. One java file will be generated for each soy file, containing template param builders
 * for each template in the soy file.
 *
 * <p>For example, "foo.soy" containing templates "bar" and "baz" would result in FooTemplates.java,
 * with inner classes Bar and Baz.
 */
public final class GenInvocationBuildersVisitor
    extends AbstractSoyNodeVisitor<ImmutableList<GeneratedFile>> {

  private static final Logger logger =
      Logger.getLogger(GenInvocationBuildersVisitor.class.getName());

  private final SoyFileNodeTransformer transformer;

  private IndentedLinesBuilder ilb; // Line formatter for the generated code.
  private ImmutableList.Builder<GeneratedFile> generatedFiles; // The generated Java files to write.

  public GenInvocationBuildersVisitor(String javaPackage, TemplateRegistry templateRegistry) {
    this.transformer = new SoyFileNodeTransformer(javaPackage, templateRegistry);
  }

  @Override
  public ImmutableList<GeneratedFile> exec(SoyNode node) {
    generatedFiles = new ImmutableList.Builder<>();
    ilb = null;
    visit(node);

    ImmutableList<GeneratedFile> builtFileList = generatedFiles.build();
    logWarningIfFilenamesNotUnique(builtFileList);
    return builtFileList;
  }

  @Override
  protected void visitSoyFileSetNode(SoyFileSetNode node) {
    for (SoyFileNode soyFile : node.getChildren()) {
      visit(soyFile);
    }
  }

  @Override
  protected void visitSoyFileNode(SoyFileNode soyFile) {
    FileInfo fileInfo = transformer.transform(soyFile);
    ilb = new IndentedLinesBuilder(2);
    appendFileHeaderAndImports(fileInfo);

    String javaClassNameForSoyFile = fileInfo.className();

    // Start of *FooTemplates class.
    appendJavadoc(
        ilb,
        "Wrapper class containing {@link BaseSoyTemplateImpl} builders for each template in: "
            + fileInfo.soyFileName()
            + ".",
        /* forceMultiline= */ false,
        /* wrapAt100Chars= */ true);
    ilb.appendLine("@Generated(\"com.google.template.soy.SoyParseInfoGenerator\")");
    ilb.appendLine("public final class " + javaClassNameForSoyFile + " {");

    ilb.increaseIndent();

    // Add FooParams subclasses for the templates in this file.
    generateParamsClassesForEachTemplate(fileInfo);

    // End of *FooTemplates class.
    ilb.decreaseIndent();
    ilb.appendLine("}");

    // Add the file name and contents to the list of generated files to write.
    String fileName = javaClassNameForSoyFile + ".java";
    generatedFiles.add(GeneratedFile.create(fileName, ilb.toString()));
    ilb = null;
  }

  /** For each public, non-delegate template in the given soy file, generates a Foo inner class. */
  private void generateParamsClassesForEachTemplate(FileInfo soyFile) {
    soyFile
        .templates()
        .forEach(
            t -> {
              switch (t.status()) {
                case HANDLED:
                  visitTemplateInfo(t);
                  break;
                case NAME_COLLISION:
                  logDuplicateTemplateNameWarning(t.templateName(), t.className());
                  break;
                case RESERVED_NAME:
                  logger.warning(
                      "When generating soy java invocation builders, soy template: "
                          + t.templateNameForUserMsgs()
                          + " generated a Java UpperCamelCase that is reserved.");
                  break;
              }
            });
  }

  /**
   * Writes a Foo subclass for the given template. The class extends {@link
   * com.google.template.soy.data.BaseSoyTemplateImpl}, which implements {@link
   * com.google.template.soy.data.SoyTemplate}.
   */
  private void visitTemplateInfo(TemplateInfo template) {
    String paramsClass = template.className();

    // Start of Foo class.
    String templateDescription = template.soyDocDesc();
    ilb.appendLine();
    appendJavadoc(
        ilb,
        "Template params for "
            + template.templateNameForUserMsgs()
            + (templateDescription != null ? ": " + templateDescription : "."),
        /* forceMultiline= */ false,
        /* wrapAt100Chars= */ true);
    ilb.appendLine("public static final class " + paramsClass + " extends BaseSoyTemplateImpl {");
    ilb.increaseIndent();
    ilb.appendLine();
    ilb.appendLine(
        "private static final String TEMPLATE_NAME = \"" + template.templateName() + "\";");
    ilb.appendLine();

    appendFutureWrapperMethod(paramsClass);

    // Constructor for Foo.
    ilb.appendLine("private " + paramsClass + "(java.util.Map<String, SoyValueProvider> data) {");
    ilb.increaseIndent();
    ilb.appendLine("super(TEMPLATE_NAME, data);");
    ilb.decreaseIndent();
    ilb.appendLine("}");

    ilb.appendLine();
    appendParamsBuilderClass(template, paramsClass);

    // End of Foo class.
    ilb.decreaseIndent();
    ilb.appendLine("}");
    ilb.appendLine();
  }

  /**
   * Adds a static method to each Params class: {@code public static SoyTemplate.AsyncWrapper<Foo>
   * wrapFuture(ListenableFuture<Foo>)}. This utility is needed for supporting Producers + some Apps
   * Framework utility classes.
   *
   * @see com.google.apps.framework.template.StructuredPageResponse
   */
  private void appendFutureWrapperMethod(String paramsClass) {
    appendJavadoc(
        ilb,
        "Wraps a ListenableFuture<"
            + paramsClass
            + "> as a SoyTemplate.AsyncWrapper<"
            + paramsClass
            + ">",
        false,
        true);
    ilb.appendLine(
        "public static SoyTemplate.AsyncWrapper<"
            + paramsClass
            + "> wrapFuture(ListenableFuture<"
            + paramsClass
            + "> paramsFuture) {");
    ilb.increaseIndent();
    ilb.appendLine("return new SoyTemplate.AsyncWrapper<>(TEMPLATE_NAME, paramsFuture);");
    ilb.decreaseIndent();
    ilb.appendLine("}");
    ilb.appendLine();
  }

  /**
   * Appends a builder class for template "foo" with parameter setting methods. This class extends
   * the {@link com.google.template.soy.data.BaseSoyTemplateImpl.AbstractBuilder} class.
   */
  private void appendParamsBuilderClass(TemplateInfo template, String templateParamsClassname) {
    appendJavadoc(ilb, "Creates a new Builder instance.", false, true);
    ilb.appendLine("public static Builder builder() {");
    ilb.increaseIndent();
    ilb.appendLine("return new Builder();");
    ilb.decreaseIndent();
    ilb.appendLine("}");
    ilb.appendLine();

    // Will contain handled and unhandled params. We include some types of unhandled params so that
    // they still end up in the generated list of params.
    List<ParamInfo> combinedParams =
        template.params().stream()
            .filter(
                info -> {
                  switch (info.status()) {
                    case HANDLED:
                    case UNHANDLED_TYPE:
                      return true;
                    case NAME_COLLISION:
                      logDuplicateParamNameWarning(
                          info.name(), info.setterName(), template.templateName());
                      return true;
                    case JAVA_INCOMPATIBLE:
                      break;
                    case INDIRECT_INCOMPATIBLE_TYPES:
                      logger.warning(
                          String.format(
                              "Parameter '%s' in %s has different types in different templates. No"
                                  + " parameter setter generated.",
                              info.name(), template.templateName()));
                      break;
                  }
                  return false;
                })
            .collect(Collectors.toList());

    if (combinedParams.stream().map(ParamInfo::param).noneMatch(Parameter::isRequired)) {
      appendJavadoc(
          ilb,
          "Creates a new instance of "
              + templateParamsClassname
              + " with no parameters set. This method was generated because all template"
              + " parameters are optional.",
          false,
          true);
      ilb.appendLine("public static " + templateParamsClassname + " getDefaultInstance() {");
      ilb.increaseIndent();
      ilb.appendLine("return builder().build();");
      ilb.decreaseIndent();
      ilb.appendLine("}");
      ilb.appendLine();
    }

    // Start of Foo.Builder class.
    ilb.appendLine("@CanIgnoreReturnValue");
    ilb.appendLine(
        "public static class Builder extends AbstractBuilder<Builder, "
            + templateParamsClassname
            + "> {");
    ilb.appendLine();
    ilb.increaseIndent();

    // Add a constant ImmutableSet of type {@link
    // com.google.template.soy.data.BaseSoyTemplateImpl.Param}
    // containing metadata about the template's params.
    String paramsSetConstantName = "PARAMS";
    appendParamsImmutableSetConstant(paramsSetConstantName, combinedParams);

    // Constructor for Foo.Builder.
    ilb.appendLine("private Builder() {");
    ilb.increaseIndent();
    ilb.appendLine("super(TEMPLATE_NAME, " + paramsSetConstantName + ");");
    ilb.decreaseIndent();
    ilb.appendLine("}");
    ilb.appendLine();

    // #buildInternal() for FooTemplate.Builder.
    ilb.appendLine("@Override");
    ilb.appendLine(
        "protected "
            + templateParamsClassname
            + " buildInternal(String name, ImmutableMap<String, SoyValueProvider> data) {");
    ilb.increaseIndent();
    ilb.appendLine("return new " + templateParamsClassname + "(data);");
    ilb.decreaseIndent();
    ilb.appendLine("}");

    // Add setters for each direct template param.
    combinedParams.stream()
        .filter(p -> p.status() == ParamStatus.HANDLED)
        .forEach(param -> writeSettersForParam(param));

    ilb.appendLine();

    // End of FooTemplateInvocation.Builder class.
    ilb.decreaseIndent();
    ilb.appendLine("}");
  }

  /** Appends the file header and imports for the generated *FooTemplates.java */
  private void appendFileHeaderAndImports(FileInfo soyFile) {
    // Header.
    ilb.appendLine("// This file was automatically generated by the Soy compiler.");
    ilb.appendLine("// Please don't edit this file by hand.");
    ilb.appendLine("// source: " + soyFile.soyFilePath()); // For Code Search link.
    ilb.appendLine();
    ilb.appendLine("package " + soyFile.packageName() + ";");
    ilb.appendLine();

    // Imports.
    ilb.appendLine("import static com.google.common.collect.ImmutableList.toImmutableList;");
    ilb.appendLine("import static com.google.common.collect.ImmutableMap.toImmutableMap;");
    ilb.appendLine();
    ilb.appendLine("import com.google.common.base.Preconditions;");
    ilb.appendLine("import com.google.common.collect.ImmutableList;");
    ilb.appendLine("import com.google.common.collect.ImmutableMap;");
    ilb.appendLine("import com.google.common.collect.ImmutableSet;");
    ilb.appendLine("import com.google.common.collect.Streams;");
    ilb.appendLine("import com.google.common.html.types.SafeHtml;");
    ilb.appendLine("import com.google.common.html.types.SafeScript;");
    ilb.appendLine("import com.google.common.html.types.SafeStyle;");
    ilb.appendLine("import com.google.common.html.types.SafeStyleSheet;");
    ilb.appendLine("import com.google.common.html.types.SafeUrl;");
    ilb.appendLine("import com.google.common.html.types.TrustedResourceUrl;");
    ilb.appendLine("import com.google.common.util.concurrent.ListenableFuture;");
    ilb.appendLine("import com.google.errorprone.annotations.CanIgnoreReturnValue;");
    ilb.appendLine("import com.google.template.soy.data.BaseSoyTemplateImpl;");
    ilb.appendLine("import com.google.template.soy.data.SanitizedContent;");
    ilb.appendLine("import com.google.template.soy.data.SoyTemplate;");
    ilb.appendLine("import com.google.template.soy.data.SoyValueConverter;");
    ilb.appendLine("import com.google.template.soy.data.SoyValueProvider;");
    ilb.appendLine("import java.util.concurrent.Future;");
    ilb.appendLine("import javax.annotation.Generated;");
    ilb.appendLine("import javax.annotation.Nullable;");
    ilb.appendLine();
    ilb.appendLine();
  }

  /**
   * Appends a constant ImmutableSet of type {@link
   * com.google.template.soy.data.BaseSoyTemplateImpl.Param} containing metadata about the
   * template's params.
   */
  private void appendParamsImmutableSetConstant(String constantName, List<ParamInfo> params) {
    ImmutableList<String> genCodeForCreatingParams =
        params.stream()
            .map(
                p -> {
                  String factory = "optional";
                  if (p.param().isRequired()) {
                    factory = "required";
                  } else if (p.indirect()) {
                    factory = "indirect";
                  }
                  return String.format("BaseSoyTemplateImpl.Param.%s(\"%s\")", factory, p.name());
                })
            .collect(toImmutableList());

    ilb.appendLineStart("private static final ImmutableSet<Param> " + constantName + " = ");
    appendImmutableSetInline(ilb, "<BaseSoyTemplateImpl.Param>", genCodeForCreatingParams);
    ilb.appendLineEnd(";");
    ilb.appendLine();
  }

  /**
   * Writes setter methods each of the java types that this param can be (e.g union int | string
   * would generate setFoo(int) and setFoo(string)).
   *
   * <p>TODO(b/77550695): Update docs for how we handle futures.
   */
  private void writeSettersForParam(ParamInfo param) {
    // Add setters for this param.
    param.javaTypes().forEach(javaType -> writeSetter(ilb, param, javaType));

    // For now only write the future interface if the setter is not already overloaded
    switch (param.futureStatus()) {
      case HANDLED:
        for (JavaType futureType : param.futureTypes()) {
          writeFutureSetter(ilb, param, new FutureJavaType(futureType));
        }
        break;
      case NAME_COLLISION:
        logger.warning(
            String.format(
                "Achievement unlocked. You have a template with parameters named %s and"
                    + " %sFuture, preventing a future setter from being created for the first"
                    + " parameter.",
                param.name(), param.name()));
        break;
      case UNHANDLED:
        break;
    }
  }

  /** Writes a setter method for the given param and java type. */
  private static void writeSetter(IndentedLinesBuilder ilb, ParamInfo param, JavaType javaType) {
    String paramName = param.name();
    String javaTypeString = javaType.toJavaTypeString();
    String paramDescription = param.param().getDescription();
    ilb.appendLine();
    appendJavadoc(
        ilb,
        "Sets "
            + paramName
            + (Strings.isNullOrEmpty(paramDescription) ? "." : ": " + paramDescription),
        /* forceMultiline= */ false,
        /* wrapAt100Chars= */ true);

    // Add @Nullable if the type is nullable AND this isn't a proto/proto enum.
    // TODO(b/140632665): Add fix for inserting @Nullable after proto package and before proto name.
    if (javaType.isNullable()
        && !(javaType instanceof ProtoEnumJavaType)
        && !(javaType instanceof ProtoJavaType)) {
      ilb.appendLine(
          ("public Builder " + param.setterName())
              + ("(@Nullable " + javaTypeString + " value) {"));
    } else {
      ilb.appendLine(
          ("public Builder " + param.setterName()) + ("(" + javaTypeString + " value) {"));
    }
    ilb.increaseIndent();

    String newVariableName = javaType.appendRunTimeOperations(ilb, "value");
    ilb.appendLine("return setParam(\"" + paramName + "\", " + newVariableName + ");");
    ilb.decreaseIndent();
    ilb.appendLine("}");
  }

  /** Writes a setter method for the given param and java type. */
  private static void writeFutureSetter(
      IndentedLinesBuilder ilb, ParamInfo param, FutureJavaType javaType) {

    ilb.appendLine();
    appendJavadoc(
        ilb,
        "Future compatible version of {@link #"
            + param.setterName()
            + "("
            + stripGenerics(javaType.getType().toJavaTypeString())
            + ")}.",
        /* forceMultiline= */ false,
        /* wrapAt100Chars= */ true);
    ilb.appendLine(
        "public Builder "
            + param.futureSetterName()
            + "("
            + javaType.toJavaTypeString()
            + " future) {");
    ilb.increaseIndent();

    ilb.appendLine(
        "return setParam(\"" + param.name() + "\", Preconditions.checkNotNull(future));");
    ilb.decreaseIndent();
    ilb.appendLine("}");
  }

  private static String stripGenerics(String type) {
    String newType = type;
    do {
      type = newType;
      newType = type.replaceAll("<[^>]*>", "");
    } while (!newType.equals(type));
    return newType;
  }

  /**
   * Logs a warning if two templates in the same soy file mapped to the same UpperCamelCase java
   * class name.
   */
  private static void logDuplicateTemplateNameWarning(
      String templateName, String generatedClassName) {
    logger.warning(
        "When generating soy java invocation builders, soy template: "
            + templateName
            + " generated the same Java"
            + " UpperCamelCase name as another template in this file.\n"
            + " This template was skipped during invocation builder generation.\n"
            + " To use this api, all soy template names in a given file should be"
            + " unique when converted to UpperCamelCase (with non-alphanumeric characters"
            + " stripped).\n"
            + "The generated Java class name was: "
            + generatedClassName
            + ".");
  }

  /**
   * Logs a warning if two params generate the same upper camel case name (which means we need to
   * skip over the param and not generate setters for it.
   */
  private static void logDuplicateParamNameWarning(
      String templateParamName, String setterName, String templateName) {
    logger.warning(
        String.format(
            "When generating soy java invocation builders, soy template: %s"
                + " had multiple parameters that generated the same setter method name: %s"
                + ".\nParam: %s is being skipped (no setters will be generated for this param).\n"
                + " To use this api, all parameter names for a given template should be"
                + " unique when converted to UpperCamelCase (with non-alphanumeric characters"
                + " stripped).\n",
            templateName, setterName, templateParamName));
  }

  /** Logs a warning if two soy files mapped to the same generated java file name. */
  private static void logWarningIfFilenamesNotUnique(ImmutableList<GeneratedFile> files) {
    ImmutableList<String> duplicateFilenames =
        files.stream()
            .collect(Collectors.groupingBy(GeneratedFile::fileName, Collectors.counting()))
            .entrySet()
            .stream()
            .filter(e -> e.getValue() > 1) // We only care about duplicate filenames.
            .map(e -> e.getKey())
            .collect(toImmutableList());

    for (String fileName : duplicateFilenames) {
      logger.warning(
          "While generating Soy Java invocation builders, multiple files in this soy fileset"
              + " mapped to the same file name: "
              + fileName
              + ".\n"
              + " To use this api, soy file names should be unique when"
              + " converted to UpperCamelCase (with non-alpha-numeric characters stripped).\n");
    }
  }
}