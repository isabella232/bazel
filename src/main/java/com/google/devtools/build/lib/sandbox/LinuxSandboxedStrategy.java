// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionStatusMessage;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.SpawnInputExpander;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;

/** Strategy that uses sandboxing to execute a process. */
@ExecutionStrategy(
  name = {"sandboxed", "linux-sandbox"},
  contextType = SpawnActionContext.class
)
public class LinuxSandboxedStrategy extends SandboxStrategy {

  public static boolean isSupported(CommandEnvironment cmdEnv) {
    return OS.getCurrent() == OS.LINUX && LinuxSandboxRunner.isSupported(cmdEnv);
  }

  private final SandboxOptions sandboxOptions;
  private final BlazeDirectories blazeDirs;
  private final Path execRoot;
  private final boolean verboseFailures;
  private final SpawnInputExpander spawnInputExpander;
  private final Path inaccessibleHelperFile;
  private final Path inaccessibleHelperDir;
  private final LinuxSandboxRootfsManager rootfsManager;

  private LinuxSandboxedStrategy(
      CommandEnvironment cmdEnv,
      BuildRequest buildRequest,
      Path sandboxBase,
      boolean verboseFailures,
      Path inaccessibleHelperFile,
      Path inaccessibleHelperDir,
      LinuxSandboxRootfsManager rootfsManager) {
    super(
        cmdEnv,
        buildRequest,
        sandboxBase,
        verboseFailures,
        buildRequest.getOptions(SandboxOptions.class));
    this.sandboxOptions = buildRequest.getOptions(SandboxOptions.class);
    this.blazeDirs = cmdEnv.getDirectories();
    this.execRoot = cmdEnv.getExecRoot();
    this.verboseFailures = verboseFailures;
    this.spawnInputExpander = new SpawnInputExpander(false);
    this.inaccessibleHelperFile = inaccessibleHelperFile;
    this.inaccessibleHelperDir = inaccessibleHelperDir;
    this.rootfsManager = rootfsManager;
  }

  static LinuxSandboxedStrategy create(
      CommandEnvironment cmdEnv,
      BuildRequest buildRequest,
      Path sandboxBase,
      boolean verboseFailures)
      throws IOException {
    Path inaccessibleHelperFile = sandboxBase.getRelative("inaccessibleHelperFile");
    FileSystemUtils.touchFile(inaccessibleHelperFile);
    inaccessibleHelperFile.setReadable(false);
    inaccessibleHelperFile.setWritable(false);
    inaccessibleHelperFile.setExecutable(false);

    Path inaccessibleHelperDir = sandboxBase.getRelative("inaccessibleHelperDir");
    inaccessibleHelperDir.createDirectory();
    inaccessibleHelperDir.setReadable(false);
    inaccessibleHelperDir.setWritable(false);
    inaccessibleHelperDir.setExecutable(false);

    SandboxOptions sandboxOptions = buildRequest.getOptions(SandboxOptions.class);
    BlazeDirectories blazeDirs = cmdEnv.getDirectories();
    String rootfsCachePath = sandboxOptions.sandboxRootfsCachePath;
    if (rootfsCachePath == null || rootfsCachePath.isEmpty()) {
	rootfsCachePath = blazeDirs.getOutputBase().getRelative("rootfs").getPathString();
    }
    LinuxSandboxRootfsManager rootfsManager = null;
    if (sandboxOptions.sandboxRootfs != null) {
      rootfsManager = LinuxSandboxRootfsManager.create(
          blazeDirs.getFileSystem(),
          sandboxOptions.sandboxRootfs,
          rootfsCachePath,
          cmdEnv);
    }

    return new LinuxSandboxedStrategy(
        cmdEnv,
        buildRequest,
        sandboxBase,
        verboseFailures,
        inaccessibleHelperFile,
        inaccessibleHelperDir,
        rootfsManager);
  }

  @Override
  protected void actuallyExec(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      AtomicReference<Class<? extends SpawnActionContext>> writeOutputFiles)
      throws IOException, ExecException, InterruptedException {
    actionExecutionContext
        .getEventBus()
        .post(ActionStatusMessage.runningStrategy(spawn.getResourceOwner(), "linux-sandbox"));
    SandboxHelpers.reportSubcommand(actionExecutionContext, spawn);

    // Each invocation of "exec" gets its own sandbox.
    Path sandboxPath = getSandboxRoot();
    Path sandboxExecRoot = sandboxPath.getRelative("execroot").getRelative(execRoot.getBaseName());

    Set<Path> writableDirs = getWritableDirs(sandboxExecRoot, spawn.getEnvironment());
    SymlinkedExecRoot symlinkedExecRoot = new SymlinkedExecRoot(sandboxExecRoot);
    ImmutableSet<PathFragment> outputs = SandboxHelpers.getOutputFiles(spawn);
    symlinkedExecRoot.createFileSystem(
        SandboxHelpers.getInputFiles(spawnInputExpander, execRoot, spawn, actionExecutionContext),
        outputs,
        writableDirs);

    SandboxRunner runner =
        new LinuxSandboxRunner(
            sandboxExecRoot,
            writableDirs,
            getTmpfsPaths(),
            getReadOnlyBindMounts(blazeDirs, sandboxExecRoot),
            verboseFailures,
            sandboxOptions.sandboxDebug);

    try {
      runSpawn(
          spawn,
          actionExecutionContext,
          spawn.getEnvironment(),
          symlinkedExecRoot,
          outputs,
          runner,
          writeOutputFiles);
    } finally {
      if (!sandboxOptions.sandboxDebug) {
        try {
          FileSystemUtils.deleteTree(sandboxPath);
        } catch (IOException e) {
          // This usually means that the Spawn itself exited, but still has children running that
          // we couldn't wait for, which now block deletion of the sandbox directory. On Linux this
          // should never happen, as we use PID namespaces and where they are not available the
          // subreaper feature to make sure all children have been reliably killed before returning,
          // but on other OS this might not always work. The SandboxModule will try to delete them
          // again when the build is all done, at which point it hopefully works, so let's just go
          // on here.
        }
      }
    }
  }

  @Override
  protected ImmutableSet<Path> getWritableDirs(Path sandboxExecRoot, Map<String, String> env)
      throws IOException {
    ImmutableSet.Builder<Path> writableDirs = ImmutableSet.builder();
    writableDirs.addAll(super.getWritableDirs(sandboxExecRoot, env));

    FileSystem fs = sandboxExecRoot.getFileSystem();
    writableDirs.add(fs.getPath("/dev/shm").resolveSymbolicLinks());
    writableDirs.add(fs.getPath("/tmp"));

    return writableDirs.build();
  }

  private ImmutableSet<Path> getTmpfsPaths() {
    ImmutableSet.Builder<Path> tmpfsPaths = ImmutableSet.builder();
    for (String tmpfsPath : sandboxOptions.sandboxTmpfsPath) {
      tmpfsPaths.add(blazeDirs.getFileSystem().getPath(tmpfsPath));
    }
    return tmpfsPaths.build();
  }

  private SortedMap<Path, Path> getReadOnlyBindMounts(
      BlazeDirectories blazeDirs, Path sandboxExecRoot) throws UserExecException {
    Path tmpPath = blazeDirs.getFileSystem().getPath("/tmp");
    final SortedMap<Path, Path> bindMounts = Maps.newTreeMap();
    if (rootfsManager != null) {
      Path mount = blazeDirs.getEmbeddedBinariesRoot();
      bindMounts.put(mount, mount);
      try {
        rootfsManager.addROMounts(bindMounts);
      } catch (IOException e) {
        throw new UserExecException(String.format("Error while generating rootfs mounts. %s", e.getMessage()));
      }
    }
    // DBX: unconditionally mount this stuff, so it works in the rootfs
    bindMounts.put(blazeDirs.getWorkspace(), blazeDirs.getWorkspace());
    bindMounts.put(blazeDirs.getOutputBase(), blazeDirs.getOutputBase());
    for (ImmutableMap.Entry<String, String> additionalMountPath :
        sandboxOptions.sandboxAdditionalMounts) {
      try {
        final Path mountTarget = blazeDirs.getFileSystem().getPath(additionalMountPath.getValue());
        // If source path is relative, treat it as a relative path inside the execution root
        final Path mountSource = sandboxExecRoot.getRelative(additionalMountPath.getKey());
        // If a target has more than one source path, the latter one will take effect.
        bindMounts.put(mountTarget, mountSource);
      } catch (IllegalArgumentException e) {
        throw new UserExecException(
            String.format("Error occurred when analyzing bind mount pairs. %s", e.getMessage()));
      }
    }
    for (Path inaccessiblePath : getInaccessiblePaths()) {
      if (inaccessiblePath.isDirectory(Symlinks.NOFOLLOW)) {
        bindMounts.put(inaccessiblePath, inaccessibleHelperDir);
      } else {
        bindMounts.put(inaccessiblePath, inaccessibleHelperFile);
      }
    }
    validateBindMounts(bindMounts);
    return bindMounts;
  }

  /**
   * This method does the following things: - If mount source does not exist on the host system,
   * throw an error message - If mount target exists, check whether the source and target are of the
   * same type - If mount target does not exist on the host system, throw an error message
   *
   * @param bindMounts the bind mounts map with target as key and source as value
   * @throws UserExecException
   */
  private void validateBindMounts(SortedMap<Path, Path> bindMounts) throws UserExecException {
    for (SortedMap.Entry<Path, Path> bindMount : bindMounts.entrySet()) {
      final Path source = bindMount.getValue();
      final Path target = bindMount.getKey();
      // Mount source should exist in the file system
      if (!source.exists()) {
        throw new UserExecException(String.format("Mount source '%s' does not exist.", source));
      }
      // If target exists, but is not of the same type as the source, then we cannot mount it.
      if (target.exists()) {
        boolean areBothDirectories = source.isDirectory() && target.isDirectory();
        boolean isSourceFile = source.isFile() || source.isSymbolicLink();
        boolean isTargetFile = target.isFile() || target.isSymbolicLink();
        boolean areBothFiles = isSourceFile && isTargetFile;
        if (!(areBothDirectories || areBothFiles)) {
          // Source and target are not of the same type; we cannot mount it.
          throw new UserExecException(
              String.format(
                  "Mount target '%s' is not of the same type as mount source '%s'.",
                  target, source));
        }
      } else if (false) { // DBX: targets often don't exist until we're in chrooted in the rootfs
        // Mount target should exist in the file system
        throw new UserExecException(
            String.format(
                "Mount target '%s' does not exist. Bazel only supports bind mounting on top of "
                    + "existing files/directories. Please create an empty file or directory at "
                    + "the mount target path according to the type of mount source.",
                target));
      }
    }
  }

  public String getActionHashKey() {
    if (rootfsManager != null) {
      String labelString = rootfsManager.getRootfsLabel().getDefaultCanonicalForm();
      return "sandbox5" + labelString;
    } else {
      return "sandbox5";
    }
  }
}
