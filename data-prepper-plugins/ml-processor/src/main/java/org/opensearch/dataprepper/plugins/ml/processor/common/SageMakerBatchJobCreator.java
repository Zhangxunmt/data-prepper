/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.ml.processor.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.ml.processor.MLProcessorConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.opensearch.dataprepper.plugins.ml.processor.util.MlCommonRequester.sendRequestToMLCommons;

public class SageMakerBatchJobCreator implements MLBatchJobCreator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String sagemakerPayload = "{\"parameters\":{\"TransformInput\":{\"ContentType\":\"application/json\","
            + "\"DataSource\":{\"S3DataSource\":{\"S3DataType\":\"ManifestFile\",\"S3Uri\":\"\"}},"
            + "\"SplitType\":\"Line\"},\"TransformJobName\":\"\","
            + "\"TransformOutput\":{\"AssembleWith\":\"Line\",\"Accept\":\"application/json\","
            + "\"S3OutputPath\":\"s3://offlinebatch/sagemaker/output\"}}}";

    @Override
    public void createMLBatchJob(Collection<Record<Event>> records, MLProcessorConfig mlProcessorConfig) {
        // Get the S3 manifest
        String customerBucket = records.stream()
                .findAny()
                .map(record -> {
                    return record.getData().getJsonNode().get("bucket").asText();
                })
                .orElse(null);   // Use null if no record is found
        System.out.println("customerBucket is " + customerBucket);
        String commonPrefix = findCommonPrefix(records);
        System.out.println("commonPrefix is " + commonPrefix);

        String manifestUrl = generateManifest(records, customerBucket, commonPrefix, mlProcessorConfig);
        System.out.println("manifestUrl is " + manifestUrl);

        String payload = createPayloadSageMaker(manifestUrl, mlProcessorConfig);
        System.out.println("payload is: " + payload);
        sendRequestToMLCommons(payload, mlProcessorConfig);

    }

    private String findCommonPrefix(Collection<Record<Event>> records) {
        List<String> keys = new ArrayList<>();
        for (Record<Event> record : records) {
            keys.add(record.getData().getJsonNode().get("key").asText());
        }

        if (keys.isEmpty()) return "";

        String prefix = keys.get(0);
        for (int i = 1; i < keys.size(); i++) {
            prefix = findCommonPrefix(prefix, keys.get(i));
            System.out.println("prefix is " + prefix);
            if (prefix.isEmpty()) break;
        }

        return prefix;
    }

    private String findCommonPrefix(String s1, String s2) {
        int minLength = Math.min(s1.length(), s2.length());
        int i = 0;

        while (i < minLength && s1.charAt(i) == s2.charAt(i)) {
            i++;
        }

        // Find the last occurrence of '/' before or at the mismatch
        int lastSlashIndex = s1.lastIndexOf('/', i - 1);
        return (lastSlashIndex >= 0) ? s1.substring(0, lastSlashIndex + 1) : "";
    }


    private String generateManifest(Collection<Record<Event>> records, String customerBucket, String prefix, MLProcessorConfig mlProcessorConfig) {
        try {
            // Generate timestamp
            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String folderName = prefix + "batch-" + timestamp;
            String fileName = folderName + "/batch-" + timestamp + ".manifest";
            System.out.println("fileName is " + fileName);

            // Construct JSON output
            JSONArray manifestArray = new JSONArray();
            manifestArray.put(new JSONObject().put("prefix", "s3://" + customerBucket + "/"));

            for (Record<Event> record : records) {
                String key = record.getData().getJsonNode().get("key").asText();
                manifestArray.put(key);
            }

            // Convert JSON to bytes
            byte[] jsonData = manifestArray.toString(4).getBytes();

            // Upload to S3
            S3Client s3 = S3Client.builder()
                    .region(mlProcessorConfig.getAwsAuthenticationOptions().getAwsRegion()) // Change to your preferred region
                    .build();

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(customerBucket)
                    .key(fileName)
                    .build();

            s3.putObject(putObjectRequest, RequestBody.fromBytes(jsonData));

            return "s3://" + customerBucket + "/" + fileName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String createPayloadSageMaker(String s3Url, MLProcessorConfig mlProcessorConfig) {
        String jobName = generateJobName();

        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(sagemakerPayload);
            ((ObjectNode) rootNode.at("/parameters/TransformInput/DataSource/S3DataSource")).put("S3Uri", s3Url);
            ((ObjectNode) rootNode.at("/parameters")).put("TransformJobName", jobName);
            ((ObjectNode) rootNode.at("/parameters/TransformOutput")).put("S3OutputPath", mlProcessorConfig.getOutputPath());

            return OBJECT_MAPPER.writeValueAsString(rootNode);
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct JSON payload", e);
        }
    }

}
