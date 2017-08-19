/*
 * Copyright 2017 Google Inc.
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
package com.google.template.soy.jssrc.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.truth.StringSubject;
import com.google.template.soy.SoyFileSetParser.ParseResult;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.LoggableElement;
import com.google.template.soy.logging.LoggingConfig;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.types.SoyTypeProvider;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.proto.SoyProtoTypeProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class VeLogInstrumentationVisitorTest {
  private static final ValidatedLoggingConfig LOGGING_CONFIG =
      ValidatedLoggingConfig.create(
          LoggingConfig.newBuilder()
              .addElement(
                  LoggableElement.newBuilder()
                      .setName("Bar")
                      .setId(1L)
                      .setProtoType("soy.test.Foo")
                      .build())
              .build());
  private static final SoyGeneralOptions SOY_OPTIONS = new SoyGeneralOptions();

  @Before
  public void setUp() {
    SOY_OPTIONS.setExperimentalFeatures(ImmutableSet.<String>of("logging_support"));
  }

  @Test
  public void testVeLogInstrumentation() throws Exception {
    assertThatSourceString(runPass("")).isEqualTo("");
    assertThatSourceString(runPass("<div></div>")).isEqualTo("<div></div>");
    assertThatSourceString(runPass("{velog Bar}<div></div>{/velog}"))
        .isEqualTo("{velog Bar}<div {'data-' + xid('soylog')}=foo></div>{/velog}");
    assertThatSourceString(runPass("{velog Bar}<input/>{/velog}"))
        .isEqualTo("{velog Bar}<input {'data-' + xid('soylog')}=foo />{/velog}");
    assertThatSourceString(runPass("{velog Bar}<input id=\"1\"/>{/velog}"))
        .isEqualTo("{velog Bar}<input id=\"1\" {'data-' + xid('soylog')}=foo />{/velog}");
    assertThatSourceString(runPass("{velog Bar logonly=\"true\"}<input id=\"1\"/>{/velog}"))
        .isEqualTo(
            "{velog Bar logonly=\"true\"}"
                + "<input id=\"1\" {'data-' + xid('soylog')}=foo />{/velog}");
    assertThatSourceString(
            runPass(
                "{velog Bar data=\"soy.test.Foo(intField: 123)\"}"
                    + "<input id=\"1\" class=\"fooClass\"/>{/velog}"))
        .isEqualTo(
            "{velog Bar data=\"soy.test.Foo(intField: 123)\"}"
                + "<input id=\"1\" class=\"fooClass\" {'data-' + xid('soylog')}=foo />"
                + "{/velog}");
  }

  /** Parses the given input as a template content. */
  private static SoyFileSetNode runPass(String input) {
    String soyFile =
        Joiner.on('\n')
            .join("{namespace ns stricthtml=\"true\"}", "", "{template .t}", input, "{/template}");
    ParseResult result =
        SoyFileSetParserBuilder.forFileContents(soyFile)
            // Disable desguaring pass and manually run it later
            .desugarHtmlNodes(false)
            .typeRegistry(
                new SoyTypeRegistry(
                    ImmutableSet.<SoyTypeProvider>of(
                        new SoyProtoTypeProvider.Builder()
                            .addDescriptors(com.google.template.soy.testing.Foo.getDescriptor())
                            .buildNoFiles())))
            .setLoggingConfig(LOGGING_CONFIG)
            .options(SOY_OPTIONS)
            .errorReporter(ErrorReporter.exploding())
            .parse();
    TemplateRegistry templateRegistry = result.registry();
    SoyFileSetNode soyTree = result.fileSet();
    new VeLogInstrumentationVisitor(templateRegistry).exec(soyTree);
    return soyTree;
  }

  private static StringSubject assertThatSourceString(SoyFileSetNode node) {
    StringBuilder sb = new StringBuilder();
    node.getChild(0).getChild(0).appendSourceStringForChildren(sb);
    return assertThat(sb.toString());
  }
}