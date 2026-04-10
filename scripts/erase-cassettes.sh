#!/usr/bin/env bash

cd "$(dirname "$(readlink -f "${BASH_SOURCE}")")"/..

rm -rf test-harness/src/testFixtures/resources/cassettes/
mkdir -p test-harness/src/testFixtures/resources/cassettes/
