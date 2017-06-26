#!/usr/bin/python2.7

import errno
import getpass
import json
import os
import os.path
import signal
import subprocess
import sys
import time
import urllib2

BAZEL_BIN = '/usr/bin/bazel-bin'

LOG_BASE_DIR = '~/.logpusher'
LOGPUSHER_BIN = '/usr/bin/logpusher'


def _spawn_logpusher():
    with open(os.devnull, 'w') as FNULL:
        subprocess.Popen(['nohup', LOGPUSHER_BIN],
                         stdin=None, stdout=FNULL, stderr=FNULL, close_fds=True)


def _mkdir_p(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno == errno.EEXIST and os.path.isdir(path):
            pass
        else:
            raise


CACHE_METRICS_URL = 'http://localhost:4567/debug/vars'
def collect_cache_metrics():
    try:
        # Don't block for more than 1 second
        body = urllib2.urlopen(CACHE_METRICS_URL, timeout=1)
        debug_vars_kv = json.loads(body.read())
        # Filter out keys which start with "cache-"
        return {k:v for k, v in debug_vars_kv.iteritems() if k.startswith('cache-')}
    except Exception as e:
        return {}


def write_metrics(name, data):
    """
    This func writes log file on disk in
    ~/.logpusher/<name>/<name>-<timestamp>.log
    Then it spawns logpusher to collect logs in background.
    If there is no logpusher in $PATH, this function is no-op.
    There must be key in data which is equal to name - it's application data. If there is no such
    key KeyError will be raised.
    """
    if not os.path.isfile(LOGPUSHER_BIN):
        return
    # check that name field (application data) is exists in data dictionary
    data[name]
    log_dir = os.path.expanduser(os.path.join(LOG_BASE_DIR, name))
    try:
        _mkdir_p(log_dir)
    except Exception as e:
        print >>sys.stderr, 'WARNING: Unable to create path for logpusher: %s' % (e)
        print >>sys.stderr
        # no point to do anything if dir is not created
        return
    write_time = time.time()
    log_filename = '%s-%.6f.log' % (name, write_time)
    data['posted_timestamp'] = int(write_time)
    write_metrics_log(log_dir, log_filename, data)
    # start logpusher to collect, it will just exit if one already running
    _spawn_logpusher()


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


def dict_subtract(end, start):
    # Pick out keys which are present in both dicts
    return {k: end[k]-start[k] for k in (start.viewkeys() & end.viewkeys())}


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
  }

  metrics['debs'] = detect_versions()

  initial_cache_metrics = collect_cache_metrics()
  returncode = 0
  cmd = get_bazel_bin() + sys.argv[1:]
  try:
    cmd_env = os.environ.copy()
    # Delete TMPDIR from env.
    # Any path set in TMPDIR may not exist in the rootfs, and can break sandboxed actions.
    cmd_env.pop('TMPDIR', None)

    proc = subprocess.Popen(cmd, env=cmd_env)
    SignalForwarder(proc)
    returncode = proc.wait()
  except KeyboardInterrupt:
    # Signal is passed through to child processes. Wait for that to take effect.
    returncode = proc.wait()

  end = time.time()
  end_cache_metrics = collect_cache_metrics()

  metrics['bazel']['time_initiated'] = int(start)
  metrics['bazel']['duration_ms'] = int((end-start)*1000)
  metrics['bazel']['exit_code'] = returncode
  metrics['bazel']['caller'] = detect_parent_process()
  metrics['metrics'] = dict_subtract(end_cache_metrics, initial_cache_metrics)
  write_metrics('bazel', metrics)

  sys.exit(returncode)

if __name__ == '__main__':
  main()
