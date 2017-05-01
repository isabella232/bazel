#!/usr/drte/v1/python-2.7.7/bin/python2.7 -ESs

import getpass
import json
import os
import signal
import subprocess
import sys
import time

LOG_DIR_BASE='/var/log/dropbox/logpusher'
LOG_DIR=os.path.join(LOG_DIR_BASE, 'bazel')
JAVA_HOME_CANDIDATES = ['/usr/lib/jvm/zulu-8-amd64', '/usr/lib/jvm/java-8-oracle', '/usr/lib/jvm/jdk-8-oracle-x64']
BAZEL_BIN = '/usr/bin/bazel-bin'


def get_bazel_bin():
    bazel_bin = os.getenv('TESTONLY_BAZEL_BINARY_OVERRIDE', BAZEL_BIN)
    return bazel_bin.split()


class SignalForwarder(object):
  def __init__(self, proc):
    self.proc = proc
    signal.signal(signal.SIGINT, self.passthrough_signal)
    signal.signal(signal.SIGQUIT, self.passthrough_signal)
    signal.signal(signal.SIGTERM, self.passthrough_signal)

  def passthrough_signal(self, signum, stackframe):
    self.proc.send_signal(signum)


DEBS_TO_TRACK = ['bazel', 'drbe', 'drbe-tools']
def detect_versions():
  debs = {}
  try:
    with open(os.devnull, 'w') as FNULL:
      versions = subprocess.check_output(["/usr/bin/dpkg-query", "--showformat=${Package}:${Version}\n", "--show"] + DEBS_TO_TRACK, stderr=FNULL)
    for v in versions.split():
      pkg, ver = v.split(':')
      debs[pkg] = ver
  except subprocess.CalledProcessError, e:
    print >>sys.stderr, 'WARNING: Unable to query installed versions of bazel-related debs'
    print >>sys.stderr

  return debs


def detect_parent_process():
  parent_pid = os.getppid()
  with open('/proc/%d/cmdline' %(parent_pid), 'r') as parent_proc_file:
    # http://man7.org/linux/man-pages/man5/proc.5.html
    #
    # /proc/[pid]/cmdline
    # The command-line arguments appear in this file as a set of strings
    # separated by null bytes ('\0'), with a further null byte after the last
    # string.
    parent_proc = parent_proc_file.read().split('\x00')[:-1]

  return parent_proc

def setup_log_dir():
  if not os.path.isdir(LOG_DIR_BASE):
    return None

  try:
    if not os.path.isdir(LOG_DIR):
      os.mkdir(LOG_DIR)
    return LOG_DIR
  except Exception, e:
    # In case we're unable to test for presence of log dir or create it, silently ignore the failure
    print >>sys.stderr, 'WARNING: Exception while listing/creating %s: %s' % (LOG_DIR, e)
    print >>sys.stderr
    return None


def set_java_home():
  for jh in JAVA_HOME_CANDIDATES:
    if os.path.isdir(jh):
      os.environ['JAVA_HOME'] = jh
      break
  else:
    sys.exit('No Java installed')


def write_metrics_log(log_dir, log_filename, metrics):
  if not log_dir:
    # Log directory not present. Silently ignore
    return

  try:
    with open(os.path.join(log_dir, log_filename), 'w') as json_out:
      json.dump(metrics, json_out, indent=4, sort_keys=True)
  except Exception, e:
    # Writing metrics is done on a best-effort basis. Any errors while
    # doing so will be logged, but will not fail the build operation.
    print >>sys.stderr, 'WARNING: Exception while writing metrics: %s' % (e)
    print >>sys.stderr
    pass


def main():
  start = time.time()
  metrics = {
    'debs': {},
    'bazel': {
        'cmd': sys.argv,
    },
    'posted_timestamp': int(start),
  }

  set_java_home()
  log_dir = setup_log_dir()
  log_filename = 'bazel-%.6f.log' % (start)

  metrics['debs'] = detect_versions()

  returncode = 0
  cmd = get_bazel_bin() + sys.argv[1:]
  try:
    proc = subprocess.Popen(cmd)
    SignalForwarder(proc)
    returncode = proc.wait()
  except KeyboardInterrupt:
    # Signal is passed through to child processes. Wait for that to take effect.
    returncode = proc.wait()

  end = time.time()

  metrics['bazel']['time_initiated'] = int(start)
  metrics['bazel']['duration_ms'] = int((end-start)*1000)
  metrics['bazel']['exit_code'] = returncode
  metrics['bazel']['caller'] = detect_parent_process()
  write_metrics_log(log_dir, log_filename, metrics)

  sys.exit(returncode)

if __name__ == '__main__':
  main()
