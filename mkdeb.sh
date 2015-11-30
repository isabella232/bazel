#!/bin/bash

pkg_dir=$(mktemp -d ./tmp-bazel.XXXX)

function cleanup() {
  if [[ "$DEBUG" == "" ]]; then
    rm -rf $pkg_dir
  fi
}

trap cleanup EXIT

mkdir -p $pkg_dir/usr/bin $pkg_dir/etc/bash_completion.d
# Do some hacking to support multiple java installations.
cp ./output/bazel $pkg_dir/usr/bin/bazel-bin
cp bazel.sh $pkg_dir/usr/bin/bazel
chmod +x $pkg_dir/usr/bin/bazel

cp bazel.bazelrc $pkg_dir/etc

#./output/bazel build //scripts:bash_completion
cp ./bazel-bin/scripts/bazel-complete.bash $pkg_dir/etc/bash_completion.d/bazel

# oracle-java8-jdk
# oracle-java8-installer
fpm -d oracle-java8-jdk --verbose --debug --prefix / -C $pkg_dir -s dir -t deb -n "bazel" -v 1.0.$(python -c "import time; print time.strftime('%Y%m%d%H%M%S', time.gmtime(time.time()))") usr etc
