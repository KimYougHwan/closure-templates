/*
 * Copyright 2011 Google Inc.
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

package com.google.template.soy.soytree;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.Kind;
import java.util.List;

/**
 * Node representing a 'import' statement with a value expression.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class ImportNode extends AbstractSoyNode implements ExprHolderNode {

  /** The value expression that the variable is set to. */
  private final List<ExprRootNode> identifiers;

  private final StringNode path;

  /** Only CSS is supported right now. */
  public enum ImportType {
    CSS
  }

  public ImportNode(int id, SourceLocation location, StringNode path, List<GlobalNode> exprs) {
    super(id, location);
    this.identifiers = exprs.stream().map(ExprRootNode::new).collect(toImmutableList());
    this.path = path;
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private ImportNode(ImportNode orig, CopyState copyState) {
    super(orig, copyState);
    this.identifiers =
        orig.identifiers.stream().map(o -> o.copy(copyState)).collect(toImmutableList());
    this.path = orig.path.copy(copyState);
  }

  @Override
  public Kind getKind() {
    return Kind.IMPORT_NODE;
  }

  @Override
  public ImmutableList<ExprRootNode> getExprList() {
    return ImmutableList.copyOf(this.identifiers);
  }

  @Override
  public ImportNode copy(CopyState copyState) {
    return new ImportNode(this, copyState);
  }

  public boolean isSideEffectImport() {
    return getExprList().isEmpty();
  }

  private ImportType getImportType() {
    // TODO(tomnguyen): Throw an error if any aliases are extracted from CSS imports, as they do not
    // exist yet.
    if (path.getValue().endsWith(".gss") || path.getValue().endsWith(".sass")) {
      return ImportType.CSS;
    }
    // TODO(tomnguyen) Write a validation pass to verify imports.
    throw new UnsupportedOperationException("No other imports are supported right now.");
  }

  public String getPath() {
    return path.getValue();
  }

  public boolean isCss() {
    return getImportType() == ImportType.CSS;
  }

  @Override
  public String toSourceString() {
    String exprs = "";
    if (!identifiers.isEmpty()) {
      exprs =
          String.format(
              "{%s} from ",
              identifiers.stream().map(ExprRootNode::toSourceString).collect(joining(",")));
    }
    return String.format("import %s'%s'", exprs, path.getValue());
  }
}
