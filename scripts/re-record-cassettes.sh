#!/usr/bin/env bash

cd "$(dirname "$(readlink -f "${BASH_SOURCE}")")"/..

echo "--------- CASSETTE RE-RECORD, CHECKING LIVE CREDENTIALS ---------------"
if ! VCR_MODE=off ./gradlew \
  :braintrust-sdk:instrumentation:openai_2_15_0:test \
  --tests 'dev.braintrust.instrumentation.openai.v2_15_0.BraintrustOpenAITest.testCompletions' \
  :braintrust-sdk:instrumentation:anthropic_2_2_0:test \
  --tests 'dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropicTest.testWrapAnthropic' \
  :braintrust-sdk:instrumentation:genai_1_18_0:test \
  --tests 'dev.braintrust.instrumentation.genai.v1_18_0.BraintrustGenAITest.testWrapGemini' \
  :braintrust-sdk:instrumentation:aws_bedrock_2_30_0:test \
  --tests 'dev.braintrust.instrumentation.awsbedrock.v2_30_0.BraintrustAWSBedrockTest.converseProducesLlmSpan' \
  --max-workers=1 --rerun; then
  echo "Credential preflight failed; existing cassettes were not erased." >&2
  exit 1
fi
echo "--------- CASSETTE RE-RECORD, LIVE CREDENTIALS SUCCEEDED ---------------"

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
unset GOOGLE_API_KEY
unset GOOGLE_GENERATIVE_AI_API_KEY
VCR_MODE=replay ./gradlew test --rerun || exit 1
echo "--------- CASSETTE RE-RECORD SUCCEEDED ---------------"
