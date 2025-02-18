
# ml Processor

This plugin enables you to send data from your Data Prepper pipeline directly to ml-commons for machine learning related activities.

## Usage
```aidl
lambda-pipeline:
...
  processor:
    - ml:
        host: "https://search-xunzh-ml-tests-ihx7htldf7nvo2gdg25m6ehthq.us-east-1.es.amazonaws.com"
        aws_sigv4: true
        action_type: "batch_predict"
        service_name: "sagemaker"
        model_id: "RIwmqZQB_0GZKgkBBy26"
        output_path: "s3://offlinebatch/sagemaker/output"
        aws:
          region: "us-east-1"
```


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
