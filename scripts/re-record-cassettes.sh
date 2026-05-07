#!/usr/bin/env bash

cd "$(dirname "$(readlink -f "${BASH_SOURCE}")")"/..

./scripts/erase-cassettes.sh
# recording single threaded to reduce the chances we get rate limited when making real api calls
VCR_MODE=record ./gradlew test --max-workers=1 --fail-fast --rerun || exit 1
echo "--------- CASSETTE RE-RECORD, RUNNING AGAIN IN REPLAY MODE ---------------"
unset BRAINTRUST_API_KEY
unset OPENAI_API_KEY
unset ANTHROPIC_API_KEY
unset AWS_ACCESS_KEY_ID
unset AWS_SECRET_ACCESS_KEY
unset AWS_SESSION_TOKEN
unset GEMINI_API_KEY
unset GOOGLE_GENERATIVE_AI_API_KEY
VCR_MODE=replay ./gradlew test --rerun || exit 1
echo "--------- CASSETTE RE-RECORD SUCCEEDED ---------------"
