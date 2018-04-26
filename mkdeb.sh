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

mkdir -p $pkg_dir/usr/bin $pkg_dir/etc/bash_completion.d $pkg_dir/usr/share/zsh/vendor-completions

if [[ -z "$BUILD_BAZEL" ]]; then
  fail "Please set BUILD_BAZEL env var to upstream Bazel binary."
fi

"$BUILD_BAZEL" build --embed_label $(git rev-parse @) -c opt --stamp src:bazel_with_jdk scripts:bash_completion
cp ./bazel-bin/src/bazel_with_jdk $pkg_dir/usr/bin/bazel-bin
cp bazel.py $pkg_dir/usr/bin/bazel
chmod +x $pkg_dir/usr/bin/bazel

cp bazel.bazelrc $pkg_dir/usr/bin/bazel-bin.bazel-binrc

cp ./bazel-bin/scripts/bazel-complete.bash $pkg_dir/etc/bash_completion.d/bazel || fail "Can't package bash_completion"
cp scripts/zsh_completion/_bazel $pkg_dir/usr/share/zsh/vendor-completions

if [[ "$1" != "" ]]; then
  timestamp=$1
else
  timestamp=$(python -c "import time; print time.strftime('%Y%m%d%H%M%S', time.gmtime(time.time()))")
fi

# Note we depend on "gcc" only because bazel barfs if a compiler isn't installed
# on the system even if the build doesn't use the system compiler. We should
# drop the gcc dependency if that problem is ever fixed.

fpm --verbose --debug --prefix / -C $pkg_dir --deb-no-default-config-files -d gcc -s dir -t deb -n "bazel" --provides bazel --conflicts bazel-beta --replaces bazel-beta -v 1.0.$timestamp usr etc

# Beta package
fpm --verbose --debug --prefix / -C $pkg_dir --deb-no-default-config-files -d gcc -s dir -t deb -n "bazel-beta" --provides bazel --conflicts bazel --replaces bazel -v 1.0.$timestamp usr etc
