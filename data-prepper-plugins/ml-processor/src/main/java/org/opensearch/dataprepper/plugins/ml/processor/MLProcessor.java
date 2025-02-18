/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.ml.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.annotations.DataPrepperPlugin;
import org.opensearch.dataprepper.model.annotations.DataPrepperPluginConstructor;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.processor.AbstractProcessor;
import org.opensearch.dataprepper.model.processor.Processor;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.ml.processor.common.MLBatchJobCreator;
import org.opensearch.dataprepper.plugins.ml.processor.common.MLBatchJobCreatorFactory;
import org.opensearch.dataprepper.plugins.ml.processor.configuration.ServiceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collection;

@DataPrepperPlugin(name = "ml", pluginType = Processor.class, pluginConfigurationType = MLProcessorConfig.class)
public class MLProcessor extends AbstractProcessor<Record<Event>, Record<Event>> {
    public static final Logger LOG = LoggerFactory.getLogger(MLProcessor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    final MLProcessorConfig mlProcessorConfig;
    private final String whenCondition;

    @DataPrepperPluginConstructor
    public MLProcessor(final MLProcessorConfig mlProcessorConfig, final PluginMetrics pluginMetrics) {
        super(pluginMetrics);
        this.mlProcessorConfig = mlProcessorConfig;
        this.whenCondition = mlProcessorConfig.getWhenCondition();
    }

    @Override
    public Collection<Record<Event>> doExecute(Collection<Record<Event>> records) {
        // reads from input - S3 input
        if (records.size() == 0)
            return records;
        System.out.println("Received .... " + records.size() + " records");
        ServiceName serviceName = mlProcessorConfig.getServiceName();

        // Use factory to get the appropriate job creator
        MLBatchJobCreator jobCreator = MLBatchJobCreatorFactory.getJobCreator(serviceName);
        jobCreator.createMLBatchJob(records, mlProcessorConfig);

        return records;
    }

    @Override
    public void prepareForShutdown() {
    }

    @Override
    public boolean isReadyForShutdown() {
        return true;
    }

    @Override
    public void shutdown() {
    }
}
