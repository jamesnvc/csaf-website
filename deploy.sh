#!/usr/bin/env bash

set -euo pipefail

set -x

NOW=$(date +"%Y-%m-%d_%H%M%S")

git clean -fx -e config.edn
git fetch
git merge --ff
lein uberjar
JAR_NAME="csaf-${NOW}.jar"
git tag -a "deploy-$NOW" -m "Deploying at ${NOW}"
scp target/csaf-0.1.0-SNAPSHOT-standalone.jar csaf-new:~/csaf/$JAR_NAME
ssh csaf-new "bash -c 'cd ~/csaf; ln -sf ${JAR_NAME} csaf.jar; date > deployed'"
git push --tags
