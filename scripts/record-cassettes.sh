#!/usr/bin/env bash

cd "$(dirname "$(readlink -f "${BASH_SOURCE}")")"/..

export VCR_MODE=record
./gradlew test
