#!/bin/bash -e

pkg_dir=$(mktemp -d ./tmp-bazel.XXXX)

function cleanup() {
  if [[ "$DEBUG" == "" ]]; then
    rm -rf $pkg_dir
  fi
}

function fail() {
  echo "$@" >&2
  exit 1
}

trap cleanup EXIT

mkdir -p $pkg_dir/usr/bin $pkg_dir/etc/bash_completion.d

if [[ -z "$BUILD_BAZEL" ]]; then
  fail "Please set BUILD_BAZEL env var to upstream Bazel binary."
fi

CC=gcc-4.9 "$BUILD_BAZEL" build -c opt --stamp src:bazel_with_jdk scripts:bash_completion
cp ./bazel-bin/src/bazel_with_jdk $pkg_dir/usr/bin/bazel-bin
cp bazel.py $pkg_dir/usr/bin/bazel
chmod +x $pkg_dir/usr/bin/bazel

cp bazel.bazelrc $pkg_dir/usr/bin/bazel-bin.bazel-binrc

cp ./bazel-bin/scripts/bazel-complete.bash $pkg_dir/etc/bash_completion.d/bazel || fail "Can't package bash_completion"

if [[ "$1" != "" ]]; then
  timestamp=$1
else
  timestamp=$(python -c "import time; print time.strftime('%Y%m%d%H%M%S', time.gmtime(time.time()))")
fi
fpm --verbose --debug --prefix / -C $pkg_dir -s dir -t deb -n "bazel" --provides bazel --conflicts bazel-beta --replaces bazel-beta -v 1.0.$timestamp usr etc

# Beta package
fpm --verbose --debug --prefix / -C $pkg_dir -s dir -t deb -n "bazel-beta" --provides bazel --conflicts bazel --replaces bazel -v 1.0.$timestamp usr etc
