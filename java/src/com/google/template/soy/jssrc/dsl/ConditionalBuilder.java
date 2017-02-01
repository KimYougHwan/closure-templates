/*
 * Copyright 2016 Google Inc.
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

package com.google.template.soy.jssrc.dsl;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/** Builds a single {@link Conditional}. */
public final class ConditionalBuilder {

  private final ImmutableList.Builder<IfThenPair> conditions = ImmutableList.builder();
  private final CodeChunk.Builder owner;

  @Nullable private CodeChunk trailingElse = null;

  ConditionalBuilder(CodeChunk.WithValue predicate, CodeChunk consequent, CodeChunk.Builder owner) {
    this.owner = owner;
    conditions.add(new IfThenPair(predicate, consequent));
  }

  /**
   * Adds an {@code else if} clause with the given predicate and consequent to this conditional.
   */
  public ConditionalBuilder elseif_(CodeChunk.WithValue predicate, CodeChunk consequent) {
    conditions.add(new IfThenPair(predicate, consequent));
    return this;
  }

  /** Adds an {@code else} clause encapsulating the given chunk to this conditional. */
  public ConditionalBuilder else_(CodeChunk trailingElse) {
    Preconditions.checkState(this.trailingElse == null);
    this.trailingElse = trailingElse;
    return this;
  }

  /**
   * Finishes building this conditional, returning the {@link CodeChunk.Builder} that created it for
   * additional chaining.
   */
  @CheckReturnValue
  public CodeChunk.Builder endif() {
    ImmutableList<IfThenPair> pairs = conditions.build();
    if (isRepresentableAsTernaryExpression(pairs)) {
      owner.addChild(
          Ternary.create(
              pairs.get(0).predicate,
              (CodeChunk.WithValue) pairs.get(0).consequent,
              (CodeChunk.WithValue) trailingElse));
    } else {
      owner.addChild(Conditional.create(pairs, trailingElse));
    }
    return owner;
  }

  private boolean isRepresentableAsTernaryExpression(ImmutableList<IfThenPair> pairs) {
    if (pairs.size() != 1 || trailingElse == null) {
      return false;
    }

    IfThenPair ifThen = Iterables.getOnlyElement(pairs);
    return ifThen.consequent instanceof CodeChunk.WithValue
        && trailingElse instanceof CodeChunk.WithValue
        && ifThen.predicate.isRepresentableAsSingleExpression()
        && ((CodeChunk.WithValue) ifThen.consequent).isRepresentableAsSingleExpression()
        && ((CodeChunk.WithValue) trailingElse).isRepresentableAsSingleExpression();
  }
}
