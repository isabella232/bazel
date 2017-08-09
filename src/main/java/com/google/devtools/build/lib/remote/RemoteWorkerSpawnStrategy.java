package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.exec.AbstractSpawnStrategy;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.vfs.Path;

@ExecutionStrategy(
  name = {"remote-worker"},
  contextType = SpawnActionContext.class
)
final class RemoteWorkerSpawnStrategy extends AbstractSpawnStrategy {
  RemoteWorkerSpawnStrategy(Path execRoot, SpawnRunner spawnRunner) {
    super(execRoot, spawnRunner);
  }

  @Override
  public String toString() {
    return "remote-worker";
  }
}
