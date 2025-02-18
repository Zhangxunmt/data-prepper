/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.ml.processor.common;

import org.opensearch.dataprepper.plugins.ml.processor.MLProcessor;
import org.opensearch.dataprepper.plugins.ml.processor.configuration.ServiceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MLBatchJobCreatorFactory {
    private static final Logger LOG = LoggerFactory.getLogger(MLProcessor.class);

    public static MLBatchJobCreator getJobCreator(ServiceName serviceName) {
        switch (serviceName) {
            case SAGEMAKER:
                return new SageMakerBatchJobCreator();
            case BEDROCK:
                return new BedrockBatchJobCreator();
            default:
                LOG.warn("Unknown service name: {}, defaulting to SageMaker", serviceName);
                return new SageMakerBatchJobCreator();
        }
    }
}
