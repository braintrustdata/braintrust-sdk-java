#!/usr/bin/env bash

cd "$(dirname "$(readlink -f "${BASH_SOURCE}")")"/..

rm -rf src/test/resources/cassettes/
mkdir -p src/test/resources/cassettes/
