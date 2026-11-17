#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/dependency-classpath.txt
classpath="target/classes:target/test-classes:$(cat target/dependency-classpath.txt)"
exec java -ea -cp "$classpath" com.fis4food.recipe.ApiSmokeCheck "$@"
