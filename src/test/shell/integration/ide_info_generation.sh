#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Integration tests for IDE build info generation.

# Load test environment
source $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/testenv.sh \
  || { echo "testenv.sh not found!" >&2; exit 1; }

create_and_cd_client
put_bazel_on_path
write_default_bazelrc
add_to_bazelrc "build --embed_changelist=none --noshow_progress"


##### TESTS

function test_ide_build_file_generation() {
  mkdir -p com/google/example/simple
  cat > com/google/example/simple/Simple.java <<EOF
package com.google.example.simple;

public class Simple {
  public static void main(String[] args) {
    System.out.println("Hello world!");
  }
}
EOF
  mkdir -p com/google/example/complex
  cat > com/google/example/complex/Complex.java <<EOF
package com.google.example.complex;

import com.google.example.simple.Simple;

public class Complex {
  public static void main(String[] args) {
    Simple.main(args);
  }
}
EOF

  cat > com/google/example/BUILD <<EOF
java_library(
    name = "simple",
    srcs = ["simple/Simple.java"]
)

java_library(
    name = "complex",
    srcs = ["complex/Complex.java"],
    deps = [":simple"]
)
EOF

  bazel build //com/google/example:complex \
        --aspects AndroidStudioInfoAspect --output_groups "ide-info" \
    || fail "Expected success"
  [ -e bazel-bin/com/google/example/simple.aswb-build ] \
    || fail "bazel-bin/com/google/example/simple.aswb-build not found"
  [ -e bazel-bin/com/google/example/complex.aswb-build ] \
    || fail "bazel-bin/com/google/example/complex.aswb-build not found"
}

function test_detailed_result() {
  # ensure clean build.
  bazel clean && bazel shutdown

  # create files and build first time
  mkdir -p com/google/example/simple
  cat > com/google/example/simple/Simple.java <<EOF
package com.google.example.simple;

public class Simple {
  public static void main(String[] args) {
    System.out.println("Hello world!");
  }
}
EOF
  mkdir -p com/google/example/complex
  cat > com/google/example/complex/Complex.java <<EOF
package com.google.example.complex;

import com.google.example.simple.Simple;

public class Complex {
  public static void main(String[] args) {
    Simple.main(args);
  }
}
EOF

  cat > com/google/example/BUILD <<EOF
java_library(
    name = "simple",
    srcs = ["simple/Simple.java"]
)

java_library(
    name = "complex",
    srcs = ["complex/Complex.java"],
    deps = [":simple"]
)
EOF

  bazel build //com/google/example:complex \
       --aspects AndroidStudioInfoAspect --output_groups "ide-info" \
       --experimental_show_artifacts 2> $TEST_log \
    || fail "Expected success"
  [ -e bazel-bin/com/google/example/simple.aswb-build ] \
    || fail "bazel-bin/com/google/example/simple.aswb-build not found"
  [ -e bazel-bin/com/google/example/complex.aswb-build ] \
    || fail "bazel-bin/com/google/example/complex.aswb-build not found"

  expect_log '^Build artifacts:'
  expect_log "^>>>.*/com/google/example/complex.aswb-build"
  expect_log "^>>>.*/com/google/example/simple.aswb-build"

  # second build; test that up-to-date artifacts are output.
  bazel build //com/google/example:complex \
       --aspects AndroidStudioInfoAspect --output_groups "ide-info" \
       --experimental_show_artifacts 2> $TEST_log \
    || fail "Expected success"
  expect_log '^Build artifacts:'
  expect_log "^>>>.*/com/google/example/complex.aswb-build"
  expect_log "^>>>.*/com/google/example/simple.aswb-build"
}

function test_ide_resolve_output_group() {
  mkdir -p com/google/example/simple
  cat > com/google/example/simple/Simple.java <<EOF
package com.google.example.simple;

public class Simple {
  public static void main(String[] args) {
    System.out.println("Hello world!");
  }
}
EOF
  mkdir -p com/google/example/complex
  cat > com/google/example/complex/Complex.java <<EOF
package com.google.example.complex;

import com.google.example.simple.Simple;

public class Complex {
  public static void main(String[] args) {
    Simple.main(args);
  }
}
EOF

  cat > com/google/example/BUILD <<EOF
java_library(
    name = "simple",
    srcs = ["simple/Simple.java"]
)

java_library(
    name = "complex",
    srcs = ["complex/Complex.java"],
    deps = [":simple"]
)
EOF

  bazel build //com/google/example:complex \
        --aspects AndroidStudioInfoAspect --output_groups "ide-resolve" \
    || fail "Expected success"
  [ -e bazel-bin/com/google/example/libsimple.jar ] \
    || fail "bazel-bin/com/google/example/libsimple.jar not found"
  [ -e bazel-bin/com/google/example/libcomplex.jar ] \
    || fail "bazel-bin/com/google/example/libcomplex.jar not found"
}

function test_filtered_gen_jar_generation() {
  mkdir -p com/google/example
  cat > com/google/example/Test.java <<EOF
package com.google.example;
class Test {}
EOF

  cat > com/google/example/BUILD <<EOF
genrule(
    name = "gen",
    outs = ["Gen.java"],
    cmd = "echo 'package gen; class Gen {}' > \$@",
)
java_library(
    name = "test",
    srcs = ["Test.java", ":gen"],
)
EOF

  bazel build //com/google/example:test \
        --aspects AndroidStudioInfoAspect --output_groups "ide-resolve" --experimental_show_artifacts \
    || fail "Expected success"
  [ -e bazel-bin/com/google/example/libtest.jar ] \
    || fail "bazel-bin/com/google/example/libtest.jar not found"
  [ -e bazel-bin/com/google/example/test-filtered-gen.jar ] \
    || fail "bazel-bin/com/google/example/test-filtered-gen.jar not found"

  unzip bazel-bin/com/google/example/test-filtered-gen.jar
  [ -e gen/Gen.class ] \
    || fail "Filtered gen jar does not contain Gen.class"
  [ ! -e com/google/example/Test.class ] \
    || fail "Filtered gen jar incorrectly contains Test.class"
}

function test_ide_build_text_file_generation() {
  mkdir -p com/google/example/simple
  cat > com/google/example/simple/Simple.java <<EOF
package com.google.example.simple;

public class Simple {
  public static void main(String[] args) {
    System.out.println("Hello world!");
  }
}
EOF
  mkdir -p com/google/example/complex
  cat > com/google/example/complex/Complex.java <<EOF
package com.google.example.complex;

import com.google.example.simple.Simple;

public class Complex {
  public static void main(String[] args) {
    Simple.main(args);
  }
}
EOF

  cat > com/google/example/BUILD <<EOF
java_library(
    name = "simple",
    srcs = ["simple/Simple.java"]
)

java_library(
    name = "complex",
    srcs = ["complex/Complex.java"],
    deps = [":simple"]
)
EOF

  bazel build //com/google/example:complex \
        --aspects AndroidStudioInfoAspect --output_groups "ide-info-text" \
    || fail "Expected success"
  [ -e bazel-bin/com/google/example/simple.aswb-build.txt ] \
    || fail "bazel-bin/com/google/example/simple.aswb-build.txt not found"
  [ -e bazel-bin/com/google/example/complex.aswb-build.txt ] \
    || fail "bazel-bin/com/google/example/complex.aswb-build.txt not found"
}

run_suite "Test IDE info files generation"
