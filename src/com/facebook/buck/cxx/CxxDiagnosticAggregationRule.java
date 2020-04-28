/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Rule which aggregates the diagnostics for all source files for each diagnostic tool into a single
 * JSON array. Each diagnostic is generated by {@link CxxDiagnosticExtractionRule}.
 */
public class CxxDiagnosticAggregationRule
    extends ModernBuildRule<CxxDiagnosticAggregationRule.Impl> {

  public CxxDiagnosticAggregationRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> inputs) {
    super(buildTarget, projectFilesystem, ruleFinder, new Impl(inputs));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Internal buildable implementation */
  static class Impl implements Buildable {
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> inputs;
    @AddToRuleKey private final OutputPath output;

    Impl(ImmutableSortedSet<SourcePath> inputs) {
      this.inputs = inputs;
      this.output = new OutputPath(CxxDiagnosticsEnhancer.DIAGNOSTICS_JSON_FILENAME);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      Path outputPath = outputPathResolver.resolvePath(output);
      Step mkdirStep = MkdirStep.of(buildCellPathFactory.from(outputPath.getParent()));

      return ImmutableList.of(
          mkdirStep,
          new AbstractExecutionStep("diagnostics-aggregate") {
            @Override
            public StepExecutionResult execute(ExecutionContext executionContext)
                throws IOException {

              try (OutputStream outputStream = filesystem.newFileOutputStream(outputPath)) {
                try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
                  jsonGen.writeStartArray();
                  for (SourcePath extractionRuleInput : inputs) {
                    Path ruleOutputPath =
                        buildContext.getSourcePathResolver().getAbsolutePath(extractionRuleInput);
                    Optional<String> ruleJSON = filesystem.readFileIfItExists(ruleOutputPath);
                    if (!ruleJSON.isPresent()) {
                      throw new RuntimeException("Could not get diagnostic");
                    }

                    JsonNode diagnosticResult = ObjectMappers.READER.readTree(ruleJSON.get());
                    jsonGen.writeObject(diagnosticResult);
                  }
                  jsonGen.writeEndArray();
                }
              }

              return StepExecutionResults.SUCCESS;
            }
          });
    }
  }
}
