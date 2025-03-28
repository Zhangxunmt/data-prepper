
# Lambda Processor

This plugin enables you to send data from your Data Prepper pipeline directly to AWS Lambda functions for further processing.

## Usage
```aidl
lambda-pipeline:
...
  processor:
    - aws_lambda:
        aws:
            region: "us-east-1"
            sts_role_arn: "<arn>"
        function_name: "uploadToS3Lambda"
        max_retries: 3
        invocation_type: "RequestResponse"
        payload_model: "batch_event"
        batch:
            key_name: "osi_key"
            threshold:
                event_count: 10
                event_collect_timeout: 15s
                maximum_size: 3mb
```

`invocation_type` as request-response is used when the response from aws lambda comes back to dataprepper.

In batch options, an implicit batch threshold option is that if events size is 3mb, we flush it.
`payload_model` this is used to define how the payload should be constructed from a dataprepper event by converting it to corresponding json.
`payload_model` as batch_event is used when the output needs to be formed as a batch of multiple events, and a key(key_name) will be associated with the set of events.
`payload_model` as single_event is used when the output each event is sent to lambda. 
if batch option is not mentioned along with payload_model: batch_event , then batch will assume default options as follows.
default batch options:
    batch_key: "events"
    threshold: 
        event_count: 10
        maximum_size: 3mb
        event_collect_timeout: 15s


## Developer Guide

The integration tests for this plugin do not run as part of the Data Prepper build.
The following command runs the integration tests:

```
./gradlew :data-prepper-plugins:aws-lambda:integrationTest \
-Dtests.lambda.processor.region="us-east-1" \
-Dtests.lambda.processor.functionName="test-lambda-processor" \
-Dtests.lambda.processor.sts_role_arn="arn:aws:iam::<>:role/lambda-role"
```

Lambda handler used to test:
```
def lambda_handler(event, context):
    input_arr = event.get('osi_key', [])
    output = []
    if len(input_arr) == 1:
        input = input_arr[0]
        if "returnNone" in input:
           return
        if "returnString" in input:
           return "RandomString"
        if "returnObject" in input:
            return input_arr[0]
        if "returnEmptyArray" in input:
            return output
        if "returnNull" in input:
            return "null"
        if "returnEmptyMapinArray" in input:
            return [{}]
    for input in input_arr:
        input["_out_"] = "transformed";
        for k,v in input.items():
            if type(v) is str:
                input[k] = v.upper()
        output.append(input)

    return output
```


# Lambda Sink

This plugin enables you to send data from your Data Prepper pipeline directly to AWS Lambda functions for further processing.

## Usage
```aidl
lambda-pipeline:
...
  sink:
    - aws_lambda:
        aws:
            region: "us-east-1"
            sts_role_arn: "<arn>"
        function_name: "uploadToS3Lambda"
        max_retries: 3
        batch:
            key_name: "osi_key"
            threshold:
                event_count: 3
                maximum_size: 6mb
                event_collect_timeout: 15s
        dlq:
            s3:
                bucket: test-bucket
                key_path_prefix: dlq/
```

## Developer Guide

The integration tests for this plugin do not run as part of the Data Prepper build.
The following command runs the integration tests:

```
./gradlew :data-prepper-plugins:aws-lambda:integrationTest \
-Dtests.lambda.processor.region="us-east-1" \
-Dtests.lambda.processor.functionName="test-lambda-processor" \
-Dtests.lambda.processor.sts_role_arn="arn:aws:iam::<>>:role/lambda-role"


```
