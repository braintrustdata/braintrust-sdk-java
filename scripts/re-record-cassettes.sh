#!/usr/bin/env bash

cd "$(dirname "$(readlink -f "${BASH_SOURCE}")")"/..

./scripts/erase-cassettes.sh
# recording single threaded to reduce the chances we get rate limited when making real api calls
VCR_MODE=record ./gradlew test --max-workers=1 --fail-fast --rerun
echo "--------- CASSETTE RE-RECORD, RUNNING AGAIN IN REPLAY MODE ---------------"
VCR_MODE=replay ./gradlew test --rerun
echo "--------- CASSETTE RE-RECORD SUCCEEDED ---------------"
