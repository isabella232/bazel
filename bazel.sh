#!/bin/bash

# shelby and other shell hosts have java 1.6 as default.
# Hack around that for now rather than herding the cats for an upgrade.
# Wonder upon wonders, we now have multiple places to hide the jvm.
for x in /usr/lib/jvm/java-8-oracle /usr/lib/jvm/jdk-8-oracle-x64;
do
  if [ -d $x ]; then
      export JAVA_HOME=$x
      break
  fi
done
exec /usr/bin/bazel-bin "$@"
