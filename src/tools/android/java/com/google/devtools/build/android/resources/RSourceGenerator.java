// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.resources;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.android.SdkConstants;
import com.android.resources.ResourceType;
import com.google.common.collect.Iterables;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes out an R.java source. */
public class RSourceGenerator {

  public static RSourceGenerator with(
      Path outputBasePath, FieldInitializers initializers, boolean finalFields) {
    return new RSourceGenerator(outputBasePath, initializers, finalFields);
  }

  private final Path outputBasePath;
  private final FieldInitializers values;
  private final boolean finalFields;

  private RSourceGenerator(
      Path outputBasePath, FieldInitializers initializers, boolean finalFields) {
    this.outputBasePath = outputBasePath;
    this.values = initializers;
    this.finalFields = finalFields;
  }

  /** Writes the java source with the writer values to the specified package and derived dir. */
  public void write(String packageName) throws IOException {
    writeSource(packageName, values);
  }

  /**
   * Writes the java source with the passed subset of the writer values.
   *
   * @param packageName The package and the dir to write the R java source to under the output path.
   * @param symbolsToWrite A map of ResourceType to resource name that will be written. If the map
   *     specifies a resource that does not exist in the writer values, it will be ignored.
   */
  public void write(String packageName, FieldInitializers symbolsToWrite) throws IOException {
    writeSource(packageName, values.filter(symbolsToWrite));
  }

  private void writeSource(
      String packageName,
      Iterable<Map.Entry<ResourceType, Map<String, FieldInitializer>>> initializersToWrite)
      throws IOException {
    String packageDir = packageName.replace('.', '/');
    Path packagePath = outputBasePath.resolve(packageDir);
    Path rJavaPath = packagePath.resolve(SdkConstants.FN_RESOURCE_CLASS);
    Files.createDirectories(rJavaPath.getParent());

    if (Iterables.isEmpty(initializersToWrite)) {
      return;
    }

    try (BufferedWriter writer = Files.newBufferedWriter(rJavaPath, UTF_8)) {
      writer.write("/* AUTO-GENERATED FILE.  DO NOT MODIFY.\n");
      writer.write(" *\n");
      writer.write(" * This class was automatically generated by the\n");
      writer.write(" * bazel tool from the resource data it found.  It\n");
      writer.write(" * should not be modified by hand.\n");
      writer.write(" */\n");
      writer.write(String.format("package %s;\n", packageName));
      writer.write("public final class R {\n");
      for (Map.Entry<ResourceType, Map<String, FieldInitializer>> entry : initializersToWrite) {
        writer.write(
            String.format("    public static final class %s {\n", entry.getKey().getName()));
        for (Map.Entry<String, FieldInitializer> fieldEntry : entry.getValue().entrySet()) {
          fieldEntry.getValue().writeInitSource(fieldEntry.getKey(), writer, finalFields);
        }
        writer.write("    }\n");
      }
      writer.write("}");
    }
  }
}
