/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.ml.processor.util;

import org.opensearch.dataprepper.plugins.ml.processor.MLProcessor;
import org.opensearch.dataprepper.plugins.ml.processor.MLProcessorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.core.internal.http.loader.DefaultSdkHttpClientBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.AttributeMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

public class MlCommonRequester {
    private static final Aws4Signer signer;
    private static final Logger LOG = LoggerFactory.getLogger(MLProcessor.class);
    static {
        signer = Aws4Signer.create();
    }

    public static void sendRequestToMLCommons(String payload, MLProcessorConfig mlProcessorConfig) {
        String host = mlProcessorConfig.getHostUrl();
        String modelId = mlProcessorConfig.getModelId();
        String path = "/_plugins/_ml/models/" + modelId + "/" + mlProcessorConfig.getActionType().getMlCommonsActionValue();
        String url = host + path;

        RequestBody requestBody = RequestBody.fromString(payload);
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create(url))
                .contentStreamProvider(requestBody.contentStreamProvider())
                .putHeader("content-type", "application/json")
                .build();

        HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                .request(signRequest(request, mlProcessorConfig))
                .contentStreamProvider(request.contentStreamProvider().orElse(null))
                .build();

        executeHttpRequest(executeRequest);
    }

    private static void executeHttpRequest(HttpExecuteRequest executeRequest) {
        AttributeMap attributeMap = AttributeMap.builder()
                .put(SdkHttpConfigurationOption.CONNECTION_TIMEOUT, Duration.ofMillis(30000))
                .put(SdkHttpConfigurationOption.READ_TIMEOUT, Duration.ofMillis(3000))
                .put(SdkHttpConfigurationOption.MAX_CONNECTIONS, 10)
                .build();
        SdkHttpClient httpClient = new DefaultSdkHttpClientBuilder().buildWithDefaults(attributeMap);

        try {
            HttpExecuteResponse response = httpClient.prepareRequest(executeRequest).call();
            System.out.println("Making HTTP call to ML Commons...");

            handleHttpResponse(response);
        } catch (Exception e) {  // TODO: catch different exceptions and retry
            throw new RuntimeException("Failed to execute request in AWS connector", e);
        }
    }

    private static void handleHttpResponse(HttpExecuteResponse response) throws IOException {
        int statusCode = response.httpResponse().statusCode();
        String modelResponse = response.responseBody().map(MlCommonRequester::readStream).orElse("No response");

        System.out.println("Response Code: " + statusCode);
        System.out.println("Response Body: " + modelResponse);

        if (statusCode != 200) {
            throw new RuntimeException("Request failed with status code: " + statusCode);
        }
    }

    private static String readStream(AbortableInputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining());
        } catch (IOException e) {
            throw new RuntimeException("Error reading response body", e);
        }
    }

    private static SdkHttpFullRequest signRequest(SdkHttpFullRequest request, MLProcessorConfig mlProcessorConfig) {
        AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        try {
            AwsCredentials credentials = credentialsProvider.resolveCredentials();

            // Extract credentials efficiently
            String accessKey = credentials.accessKeyId();
            String secretKey = credentials.secretAccessKey();
            String sessionToken = (credentials instanceof AwsSessionCredentials)
                    ? ((AwsSessionCredentials) credentials).sessionToken()
                    : null;
            System.out.println("Access Key: " + credentials.accessKeyId());
            System.out.println("Secret Key: " + credentials.secretAccessKey());
            System.out.println("Session Token: " + sessionToken);
            // Only print credentials in debug mode or development
            if (LOG.isDebugEnabled()) {
                LOG.debug("Access Key: {}", accessKey);
                LOG.debug("Secret Key: [REDACTED]"); // Never log secret key in production
                LOG.debug("Session Token: {}", sessionToken != null ? sessionToken : "Not available");
            }

            String signingName = "es";
            Region region = mlProcessorConfig.getAwsAuthenticationOptions().getAwsRegion();

            return signRequest(request, accessKey, secretKey, sessionToken, signingName, region);
        } catch (Exception e) {
            LOG.error("Failed to sign request due to credential retrieval error", e);
            throw new RuntimeException("Unable to sign AWS request", e);
        }
    }

    private static SdkHttpFullRequest signRequest(
            SdkHttpFullRequest request,
            String accessKey,
            String secretKey,
            String sessionToken,
            String signingName,
            Region region
    ) {
        AwsCredentials credentials = sessionToken == null
                ? AwsBasicCredentials.create(accessKey, secretKey)
                : AwsSessionCredentials.create(accessKey, secretKey, sessionToken);

        Aws4SignerParams params = Aws4SignerParams
                .builder()
                .awsCredentials(credentials)
                .signingName(signingName)
                .signingRegion(region)
                .build();

        return signer.sign(request, params);
    }
}
