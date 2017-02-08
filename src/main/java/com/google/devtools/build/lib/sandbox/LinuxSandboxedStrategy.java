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

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.unix.NativePosixFiles;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Strategy that uses sandboxing to execute a process. */
@ExecutionStrategy(
  name = {"sandboxed"},
  contextType = SpawnActionContext.class
)
public class LinuxSandboxedStrategy extends SandboxStrategy {
  private static final Set<String> RW_MOUNTS = new HashSet<String>(
    Arrays.asList(new String[]{"/etc", "/run", "/srv"})
  );

  private static Boolean sandboxingSupported = null;

  public static boolean isSupported(CommandEnvironment env) {
    if (sandboxingSupported == null) {
      sandboxingSupported =
          ProcessWrapperRunner.isSupported(env) || LinuxSandboxRunner.isSupported(env);
    }
    return sandboxingSupported.booleanValue();
  }

  private final SandboxOptions sandboxOptions;
  private final BlazeDirectories blazeDirs;
  private final Path execRoot;
  private final boolean verboseFailures;
  private final String productName;
  private final boolean fullySupported;

  private final UUID uuid = UUID.randomUUID();
  private final AtomicInteger execCounter = new AtomicInteger();

  private final String rootfsBase;
  private final Label rootfsLabel;

  LinuxSandboxedStrategy(
      BuildRequest buildRequest,
      BlazeDirectories blazeDirs,
      boolean verboseFailures,
      String productName,
      boolean fullySupported,
      String rootfsBase,
      Label rootfsLabel) {
    super(
        buildRequest,
        blazeDirs,
        verboseFailures,
        buildRequest.getOptions(SandboxOptions.class));
    this.sandboxOptions = buildRequest.getOptions(SandboxOptions.class);
    this.blazeDirs = blazeDirs;
    this.execRoot = blazeDirs.getExecRoot();
    this.verboseFailures = verboseFailures;
    this.productName = productName;
    this.fullySupported = fullySupported;
    this.rootfsBase = rootfsBase;
    this.rootfsLabel = rootfsLabel;
  }

  /** Executes the given {@code spawn}. */
  @Override
  public void exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    exec(spawn, actionExecutionContext, null);
  }

  @Override
  public void exec(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      AtomicReference<Class<? extends SpawnActionContext>> writeOutputFiles)
      throws ExecException, InterruptedException {
    Executor executor = actionExecutionContext.getExecutor();

    // Certain actions can't run remotely or in a sandbox - pass them on to the standalone strategy.
    if (!spawn.isRemotable() || spawn.hasNoSandbox()) {
      SandboxHelpers.fallbackToNonSandboxedExecution(spawn, actionExecutionContext, executor);
      return;
    }

    SandboxHelpers.reportSubcommand(executor, spawn);
    SandboxHelpers.postActionStatusMessage(executor, spawn);

    // Each invocation of "exec" gets its own sandbox.
    Path sandboxPath = SandboxHelpers.getSandboxRoot(blazeDirs, productName, uuid, execCounter);
    Path sandboxExecRoot = sandboxPath.getRelative("execroot").getRelative(execRoot.getBaseName());
    Path sandboxTempDir = sandboxPath.getRelative("tmp");

    Set<Path> writableDirs = getWritableDirs(sandboxExecRoot, spawn.getEnvironment());

    SymlinkedExecRoot symlinkedExecRoot = new SymlinkedExecRoot(sandboxExecRoot, false);
    ImmutableSet<PathFragment> outputs = SandboxHelpers.getOutputFiles(spawn);
    Map<PathFragment, Path> inputMounts = getMounts(spawn, actionExecutionContext);
    try {
      symlinkedExecRoot.createFileSystem(
          inputMounts, outputs, writableDirs);
      sandboxTempDir.createDirectory();
    } catch (IOException e) {
      throw new UserExecException("I/O error during sandboxed execution", e);
    }

    SandboxRunner runner = getSandboxRunner(spawn, sandboxPath, sandboxExecRoot, sandboxTempDir, inputMounts);
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
          executor
              .getEventHandler()
              .handle(
                  Event.warn(
                      String.format(
                          "Cannot delete sandbox directory after action execution: %s (%s)",
                          sandboxPath.getPathString(), e)));
        }
      }
    }
  }

  private SandboxRunner getSandboxRunner(
      Spawn spawn, Path sandboxPath, Path sandboxExecRoot, Path sandboxTempDir, Map<PathFragment, Path> inputMounts)
      throws UserExecException {
    if (fullySupported) {
      return new LinuxSandboxRunner(
          execRoot,
          sandboxPath,
          sandboxExecRoot,
          sandboxTempDir,
          getWritableDirs(sandboxExecRoot, spawn.getEnvironment()),
          getInaccessiblePaths(),
          getTmpfsPaths(),
          getReadOnlyBindMounts(blazeDirs, sandboxExecRoot, inputMounts),
          verboseFailures,
          sandboxOptions.sandboxDebug);
    } else {
      return new ProcessWrapperRunner(execRoot, sandboxExecRoot, verboseFailures);
    }
  }

  private ImmutableSet<Path> getTmpfsPaths() {
    ImmutableSet.Builder<Path> tmpfsPaths = ImmutableSet.builder();
    for (String tmpfsPath : sandboxOptions.sandboxTmpfsPath) {
      tmpfsPaths.add(blazeDirs.getFileSystem().getPath(tmpfsPath));
    }
    tmpfsPaths.add(blazeDirs.getFileSystem().getPath("/dev/shm"));
    return tmpfsPaths.build();
  }

  private SortedMap<Path, Path> getReadOnlyBindMounts(
      BlazeDirectories blazeDirs, Path sandboxExecRoot, Map<PathFragment, Path> inputMounts) throws UserExecException {
    Path tmpPath = blazeDirs.getFileSystem().getPath("/tmp");
    final SortedMap<Path, Path> bindMounts = Maps.newTreeMap();
    // NOTE(naphat) mount embedded binaries
    Path mount = blazeDirs.getEmbeddedBinariesRoot().getRelative("build-runfiles");
    bindMounts.put(mount, mount);
    try {
      bindMounts.putAll(handleRWMounts(mountUsualUnixDirs()));

      SortedMap<Path, Path> inputMountsSorted = Maps.newTreeMap();
      for (Entry<PathFragment, Path> entry: inputMounts.entrySet()) {
        inputMountsSorted.put(sandboxExecRoot.getRelative(entry.getKey()), entry.getValue());
      }

      bindMounts.putAll(finalizeMounts(inputMountsSorted));
    } catch (IOException e) {
      throw new UserExecException(
          String.format("Error occurred while generating system mounts. %s", e.getMessage()));
    }
    // NOTE(naphat) never mount these, since we mount each input individually
    // if (blazeDirs.getWorkspace().startsWith(tmpPath)) {
    //  bindMounts.put(blazeDirs.getWorkspace(), blazeDirs.getWorkspace());
    // }
    // if (blazeDirs.getOutputBase().startsWith(tmpPath)) {
    //  bindMounts.put(blazeDirs.getOutputBase(), blazeDirs.getOutputBase());
    // }
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
      } else {
        // NOTE(naphat) relax this constraint, since when we use a custom rootfs,
        // this constraint will almost always be broken
        // // Mount target should exist in the file system
        // throw new UserExecException(
        //     String.format(
        //         "Mount target '%s' does not exist. Bazel only supports bind mounting on top of "
        //             + "existing files/directories. Please create an empty file or directory at "
        //             + "the mount target path according to the type of mount source.",
        //         target));
      }
    }
  }

  /**
   * Mount a certain set of unix directories to make the usual tools and libraries available to the
   * spawn that runs.
   *
   * Throws an exception if any of them do not exist.
   */
  private Map<Path, Path> mountUsualUnixDirs() throws IOException {
    Map<Path, Path> mounts = Maps.newTreeMap();
    FileSystem fs = blazeDirs.getFileSystem();

    if (rootfsBase == null) {
      mounts.put(fs.getPath("/bin"), fs.getPath("/bin"));
      mounts.put(fs.getPath("/sbin"), fs.getPath("/sbin"));
      mounts.put(fs.getPath("/etc"), fs.getPath("/etc"));

      // Check if /etc/resolv.conf is a symlink and mount its target
      // Fix #738
      Path resolv = fs.getPath("/etc/resolv.conf");
      if (resolv.exists() && resolv.isSymbolicLink()) {
        mounts.put(resolv.resolveSymbolicLinks(), resolv.resolveSymbolicLinks());
      }

      for (String entry : NativePosixFiles.readdir("/")) {
        if (entry.startsWith("lib")) {
          Path libDir = fs.getRootDirectory().getRelative(entry);
          mounts.put(libDir, libDir);
        }
      }
      for (String entry : NativePosixFiles.readdir("/usr")) {
        if (!entry.equals("local")) {
          Path usrDir = fs.getPath("/usr").getRelative(entry);
          mounts.put(usrDir, usrDir);
        }
      }
    } else {
      for (String entry : NativePosixFiles.readdir(rootfsBase)) {
        // do this check even though we already do the check on extraction,
        // in case the user hasn't run `bazel clean --expunge` in a while
        // and the list has changed
        if (LinuxSandboxRootfsManager.MOUNT_BLACKLIST.contains(entry)) {
          continue;
        }
        Path libDir = fs.getRootDirectory().getRelative(entry);
        Path rootfsLibDir = fs.getRootDirectory().getRelative(rootfsBase + "/" + entry);
        mounts.put(libDir, rootfsLibDir);
      }
    }

    for (Path path : mounts.values()) {
      Preconditions.checkArgument(path.exists(), "%s does not exist", path.toString());
    }
    return mounts;
  }

  /**
   * Helper method of {@link #finalizeMounts}. This method handles adding a single path
   * to the output map, including making sure it exists and adding the target of a
   * symbolic link if necessary.
   *
   * @param finalizedMounts the map to add the mapping(s) to
   * @param target the key to add to the map
   * @param source the value to add to the map
   * @param stat information about source (passed in to avoid fetching it twice)
   */
  private static void finalizeMountPath(
      Map<Path, Path> finalizedMounts, Path target, Path source, FileStatus stat) throws IOException {
    // The source must exist.
    Preconditions.checkArgument(stat != null, "%s does not exist", source.toString());
    finalizedMounts.put(target, source);

    if (stat.isSymbolicLink()) {
      Path symlinkTarget = source.resolveSymbolicLinks();
      Preconditions.checkArgument(
          symlinkTarget.exists(), "%s does not exist", symlinkTarget.toString());
      finalizedMounts.put(symlinkTarget, symlinkTarget);
    }
    // HACK(naphat) in the new scheme, the sandbox exec root is not the same
    // as the default exec root, and some our our rules get confused
    if (!source.equals(target)) {
      finalizedMounts.put(source, source);
    }
  }

  /**
   * Performs various checks on each mounted file which require stating each one.
   * Contained in one function to allow minimizing the number of syscalls involved.
   *
   * Checks for each mount if the source refers to a symbolic link and if yes, adds another mount
   * for the target of that symlink to ensure that it keeps working inside the sandbox.
   *
   * Checks for each mount if the source refers to a directory and if yes, replaces that mount with
   * mounts of all files inside that directory.
   *
   * Validates all mounts against a set of criteria and throws an exception on error.
   *
   * @return a new mounts multimap with all mounts and the added mounts.
   */
  static Map<Path, Path> finalizeMounts(Map<Path, Path> mounts) throws IOException {
    Map<Path, Path> finalizedMounts = Maps.newTreeMap();
    for (Entry<Path, Path> mount : mounts.entrySet()) {
      Path target = mount.getKey();
      Path source = mount.getValue();

      FileStatus stat = source.statNullable(Symlinks.NOFOLLOW);

      if (stat != null && stat.isDirectory()) {
        for (Path subSource : FileSystemUtils.traverseTree(source, Predicates.alwaysTrue())) {
          Path subTarget = target.getRelative(subSource.relativeTo(source));
          finalizeMountPath(
              finalizedMounts, subTarget, subSource, subSource.statNullable(Symlinks.NOFOLLOW));
        }
      } else {
        finalizeMountPath(finalizedMounts, target, source, stat);
      }
    }
    return finalizedMounts;
  }

  /**
   * For each directory in the mount map, if it is a RW mount, then mount
   * everything immediately inside the directory instead. This allows
   * the directory itself to be mounted as RW.
   *
   * @return a new mounts multimap with all mounts and the added mounts.
   */
  Map<Path, Path> handleRWMounts(Map<Path, Path> mounts) throws IOException {
    Map<Path, Path> finalizedMounts = Maps.newTreeMap();
    FileSystem fs = blazeDirs.getFileSystem();
    Path rootfsBasePath = fs.getPath("/");
    if (rootfsBase != null) {
      rootfsBasePath = fs.getPath(rootfsBase);
    }
    Path rootPath = fs.getPath("/");
    for (Entry<Path, Path> mount : mounts.entrySet()) {
      Path target = mount.getKey();
      Path source = mount.getValue();

      FileStatus stat = source.statNullable(Symlinks.NOFOLLOW);

      if (stat != null && stat.isDirectory() && RW_MOUNTS.contains(target.toString())) {
        for (String entry : NativePosixFiles.readdir(source.toString())) {
          Path subSource = source.getRelative(entry);
          Path subTarget = target.getRelative(entry);
          FileStatus subStat = subSource.statNullable(Symlinks.NOFOLLOW);
          if (subStat != null && subStat.isSymbolicLink()) {
            // if this is a symlink, and it doesn't exist, we'll have trouble mounting it.
            // so, let's just skip it.
            PathFragment symlinkTargetFragment = subSource.readSymbolicLink();
            Path symlinkTargetPath;
            if (symlinkTargetFragment.isAbsolute()) {
              symlinkTargetPath = rootfsBasePath.getRelative(symlinkTargetFragment.relativeTo(rootPath.asFragment()));
            } else {
              symlinkTargetPath = target.getRelative(symlinkTargetFragment);
            }
            if (!symlinkTargetPath.exists()) {
              continue;
            }
          }
          finalizedMounts.put(subTarget, subSource);
        }
      } else {
        finalizedMounts.put(target, source);
      }
    }
    return finalizedMounts;
  }

  public String getActionHashKey() {
    if (rootfsLabel != null) {
      String labelString = rootfsLabel.getDefaultCanonicalForm();
      return "sandbox2" + labelString;
    } else {
      return "sandbox2";
    }
  }
}
