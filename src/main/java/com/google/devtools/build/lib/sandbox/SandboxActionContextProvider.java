// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.sandbox;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ActionContextProvider;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.packages.FileTarget;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.util.OS;
import java.io.IOException;

/**
 * Provides the sandboxed spawn strategy.
 */
public final class SandboxActionContextProvider extends ActionContextProvider {

  public static final String SANDBOX_NOT_SUPPORTED_MESSAGE =
      "Sandboxed execution is not supported on your system and thus hermeticity of actions cannot "
          + "be guaranteed. See http://bazel.build/docs/bazel-user-manual.html#sandboxing for more "
          + "information. You can turn off this warning via --ignore_unsupported_sandboxing";

  @SuppressWarnings("unchecked")
  private final ImmutableList<ActionContext> contexts;

  private SandboxActionContextProvider(ImmutableList<ActionContext> contexts) {
    this.contexts = contexts;
  }

  public static SandboxActionContextProvider create(
      CommandEnvironment env, BuildRequest buildRequest) throws IOException {
    boolean verboseFailures = buildRequest.getOptions(ExecutionOptions.class).verboseFailures;
    ImmutableList.Builder<ActionContext> contexts = ImmutableList.builder();

    switch (OS.getCurrent()) {
      case LINUX:
        if (LinuxSandboxedStrategy.isSupported(env)) {
          boolean fullySupported = LinuxSandboxRunner.isSupported(env);
          if (!fullySupported
              && !buildRequest.getOptions(SandboxOptions.class).ignoreUnsupportedSandboxing) {
            env.getReporter().handle(Event.warn(SANDBOX_NOT_SUPPORTED_MESSAGE));
          }
          SandboxOptions sandboxOptions = buildRequest.getOptions(SandboxOptions.class);
          BlazeDirectories blazeDirs = env.getDirectories();
          String rootfsCachePath = sandboxOptions.sandboxRootfsCachePath;
          if (rootfsCachePath == null || rootfsCachePath.isEmpty()) {
            rootfsCachePath = blazeDirs.getOutputBase().getRelative("rootfs").getPathString();
          }
          LinuxSandboxRootfsManager rootfsManager = new LinuxSandboxRootfsManager(blazeDirs.getFileSystem(), rootfsCachePath, env.getReporter());
          Label rootfsLabel = sandboxOptions.sandboxRootfs;
          String rootfsBase = null;
          if (rootfsLabel != null) {
            try {
              Target target = env.getPackageManager().getTarget(env.getReporter(), rootfsLabel);
              if (target instanceof Rule) {
                Rule rule = (Rule) target;
                if (!rule.getRuleClass().equals("filegroup")) {
                  throw new IllegalArgumentException("sandbox_rootfs must either be a filegroup or a file target.");
                }
                ImmutableList attr = (ImmutableList) RawAttributeMapper.of(rule).getRawAttributeValue(rule, "srcs");
                if (attr.size() != 1) {
                  throw new IllegalArgumentException("sandbox_rootfs filegroup must have exactly one file.");
                }
                rootfsLabel = (Label) attr.get(0);
              } else if (!(target instanceof FileTarget)) {
                throw new IllegalArgumentException("sandbox_rootfs must either be a filegroup or a file target.");
              }
              Path rootfsArchivePath = env.getBlazeModuleEnvironment().getFileFromWorkspace(rootfsLabel);

              rootfsBase = rootfsManager.getRootfsPath(rootfsArchivePath);
            } catch (NoSuchThingException | InterruptedException e) {
              // TODO(naphat) better error handling
              throw new IllegalArgumentException(e);
            }
          }
          contexts.add(
              new LinuxSandboxedStrategy(
                  buildRequest,
                  env.getDirectories(),
                  verboseFailures,
                  env.getRuntime().getProductName(),
                  fullySupported,
                  rootfsBase,
                  rootfsLabel));
        }
        break;
      case DARWIN:
        if (DarwinSandboxRunner.isSupported()) {
          contexts.add(
              DarwinSandboxedStrategy.create(
                  buildRequest,
                  env.getClientEnv(),
                  env.getDirectories(),
                  verboseFailures,
                  env.getRuntime().getProductName()));
        } else {
          if (!buildRequest.getOptions(SandboxOptions.class).ignoreUnsupportedSandboxing) {
            env.getReporter().handle(Event.warn(SANDBOX_NOT_SUPPORTED_MESSAGE));
          }
        }
        break;
      default:
        // No sandboxing available.
    }

    return new SandboxActionContextProvider(contexts.build());
  }

  @Override
  public Iterable<ActionContext> getActionContexts() {
    return contexts;
  }
}
