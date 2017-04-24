#!/bin/bash -ex
# Test to validate wrapper does not leak bazel subprocesses.
# To execute, run
# $ ./bazel_wrapper_test.sh

# Validate that SIGTERM terminates child processes
TESTONLY_BAZEL_BINARY_OVERRIDE='/bin/sleep inf' ./bazel.py &
bazel_pid=$!

# Kill it with SIGTERM, and expect no child processes to remain
kill -TERM $bazel_pid
if [[ ! -z $(pgrep -P $$) ]]; then
    echo "Leaked child processes", $(pgrep -P $$)
fi


# Validate that SIGKILL terminates child processes
TESTONLY_BAZEL_BINARY_OVERRIDE='/bin/sleep inf' ./bazel.py &
bazel_pid=$!

# Kill it with SIGTERM, and expect no child processes to remain
kill -KILL $bazel_pid
if [[ ! -z $(pgrep -P $$) ]]; then
    echo "Leaked child processes", $(pgrep -P $$)
fi


# Validate that SIGQUIT terminates child processes
TESTONLY_BAZEL_BINARY_OVERRIDE='/bin/sleep inf' ./bazel.py &
bazel_pid=$!

# Kill it with SIGQUIT, and expect no child processes to remain
kill -QUIT $bazel_pid
if [[ ! -z $(pgrep -P $$) ]]; then
    echo "Leaked child processes", $(pgrep -P $$)
fi
