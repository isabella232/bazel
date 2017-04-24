#!/usr/drte/v1/python-2.7.7/bin/python2.7 -ESs

import getpass
import json
import os
import signal
import subprocess
import sys
import time

LOG_DIR_TEMPLATE='~/logs/dropbox/bazel'
JAVA_HOME_CANDIDATES = ['/usr/lib/jvm/zulu-8-amd64', '/usr/lib/jvm/java-8-oracle', '/usr/lib/jvm/jdk-8-oracle-x64']
BAZEL_BIN = '/usr/bin/bazel-bin'


def get_bazel_bin():
    bazel_bin = os.getenv('TESTONLY_BAZEL_BINARY_OVERRIDE', BAZEL_BIN)
    return bazel_bin.split()


def get_log_dir():
    # Like /home/username/logs/dropbox/bazel
    # User-specific log dir is required to support multi-user environments
    return os.path.expanduser(LOG_DIR_TEMPLATE)

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
    print 'WARNING: Unable to query installed versions of bazel-related debs'
    print

  return debs


def set_java_home():
  if not os.path.isdir(get_log_dir()):
    os.makedirs(get_log_dir())
  for jh in JAVA_HOME_CANDIDATES:
    if os.path.isdir(jh):
      os.environ['JAVA_HOME'] = jh
      break
  else:
    sys.exit('No Java installed')

def main():
  start = time.time()
  metrics = {
    'debs': {},
    'bazel': {
        'cmd': sys.argv,
    },
    'posted_timestamp': int(start),
  }

  logfile = os.path.join(get_log_dir(), 'bazel-%.6f.log' % (start))

  metrics['debs'] = detect_versions()

  set_java_home()

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

  parent_pid = os.getppid()
  with open('/proc/%d/cmdline' %(parent_pid), 'r') as parent_proc_file:
    # http://man7.org/linux/man-pages/man5/proc.5.html
    #
    # /proc/[pid]/cmdline
    # The command-line arguments appear in this file as a set of strings
    # separated by null bytes ('\0'), with a further null byte after the last
    # string.
    parent_proc = parent_proc_file.read().split('\x00')[:-1]

  metrics['bazel']['caller'] = parent_proc

  with open(logfile, 'w') as json_out:
    json.dump(metrics, json_out, indent=4, sort_keys=True)

  if returncode:
    print >>sys.stderr, 'Logged bazel invocation metrics to %s' % (logfile)
  sys.exit(returncode)

if __name__ == '__main__':
  main()
