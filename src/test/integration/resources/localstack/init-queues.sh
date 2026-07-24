#!/bin/sh
# LocalStack ready.d hook: create the SQS queues the pipeline expects before the Spring
# context connects. The final echo is the log line the Testcontainers wait strategy blocks on,
# so queues are guaranteed to exist before any @SqsListener starts polling.
set -e

# VisibilityTimeout is deliberately short (5s): under host load LocalStack can drop a poll,
# and a low timeout means any un-acked message reappears quickly instead of waiting the 30s
# default — keeps the async assertions fast and deterministic.
awslocal sqs create-queue --queue-name sync-events.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true,VisibilityTimeout=5
awslocal sqs create-queue --queue-name apple-commands.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false,VisibilityTimeout=5
awslocal sqs create-queue --queue-name apple-commands-results.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false,VisibilityTimeout=5
awslocal sqs create-queue --queue-name user-commands.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false,VisibilityTimeout=5
awslocal sqs create-queue --queue-name core-events \
  --attributes VisibilityTimeout=5
awslocal sqs create-queue --queue-name ia-jobs \
  --attributes VisibilityTimeout=5
awslocal sqs create-queue --queue-name telemetry-events \
  --attributes VisibilityTimeout=5

echo 'HyperBrain queues ready'
