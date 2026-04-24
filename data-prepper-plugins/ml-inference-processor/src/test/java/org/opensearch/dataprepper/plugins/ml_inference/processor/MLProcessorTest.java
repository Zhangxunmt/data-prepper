/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.ml_inference.processor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.dataprepper.aws.api.AwsCredentialsSupplier;
import org.opensearch.dataprepper.expression.ExpressionEvaluator;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.configuration.PluginSetting;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.plugin.PluginFactory;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.ml_inference.processor.common.MLBatchJobCreator;
import org.opensearch.dataprepper.plugins.ml_inference.processor.common.ModelSyncInferenceExecutor;
import io.micrometer.core.instrument.Counter;
import org.opensearch.dataprepper.plugins.ml_inference.processor.configuration.ActionType;
import org.opensearch.dataprepper.plugins.ml_inference.processor.configuration.AwsAuthenticationOptions;
import org.opensearch.dataprepper.plugins.ml_inference.processor.configuration.ServiceName;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.opensearch.dataprepper.plugins.ml_inference.processor.MLProcessor.NUMBER_OF_ML_PROCESSOR_FAILED;
import static org.opensearch.dataprepper.plugins.ml_inference.processor.MLProcessor.NUMBER_OF_ML_PROCESSOR_SUCCESS;

@ExtendWith(MockitoExtension.class)
public class MLProcessorTest {

    @Mock private MLProcessorConfig mlProcessorConfig;
    @Mock private PluginMetrics pluginMetrics;
    @Mock private AwsCredentialsSupplier awsCredentialsSupplier;
    @Mock private ExpressionEvaluator expressionEvaluator;
    @Mock private Counter successCounter;
    @Mock private Counter failureCounter;
    @Mock private AwsAuthenticationOptions awsAuthenticationOptions;
    @Mock private AwsCredentialsProvider awsCredentialsProvider;
    @Mock private PluginFactory pluginFactory;
    @Mock private PluginSetting pluginSetting;

    private void setupCommonMocks() {
        MockitoAnnotations.openMocks(this);
        lenient().when(awsAuthenticationOptions.getAwsRegion()).thenReturn(Region.US_WEST_2);
        lenient().when(awsCredentialsSupplier.getProvider(any())).thenReturn(awsCredentialsProvider);
        lenient().when(mlProcessorConfig.getAwsAuthenticationOptions()).thenReturn(awsAuthenticationOptions);
        lenient().when(mlProcessorConfig.getDlqPluginSetting()).thenReturn(null);
        lenient().when(pluginMetrics.counter(NUMBER_OF_ML_PROCESSOR_SUCCESS)).thenReturn(successCounter);
        lenient().when(pluginMetrics.counter(NUMBER_OF_ML_PROCESSOR_FAILED)).thenReturn(failureCounter);
    }

    @Nested
    class BatchPredictMode {

        private MLProcessor mlProcessor;
        private MLBatchJobCreator mlBatchJobCreator;

        @BeforeEach
        void setUp() throws NoSuchFieldException, IllegalAccessException {
            mlBatchJobCreator = mock(MLBatchJobCreator.class);
            setupCommonMocks();
            when(mlProcessorConfig.getWhenCondition()).thenReturn("condition");
            lenient().when(expressionEvaluator.evaluateConditional(eq("condition"), any())).thenReturn(true);
            lenient().when(mlProcessorConfig.getServiceName()).thenReturn(ServiceName.SAGEMAKER);
            lenient().when(mlProcessorConfig.getActionType()).thenReturn(ActionType.BATCH_PREDICT);

            mlProcessor = new MLProcessor(mlProcessorConfig, pluginMetrics, pluginFactory, pluginSetting, awsCredentialsSupplier, expressionEvaluator);
            Field field = MLProcessor.class.getDeclaredField("mlBatchJobCreator");
            field.setAccessible(true);
            field.set(mlProcessor, mlBatchJobCreator);
        }

        @Test
        void testDoExecute_WithValidRecords() throws Exception {
            final Event event = mock(Event.class);
            final Record<Event> record = new Record<>(event);
            final List<Record<Event>> records = Collections.singletonList(record);

            mlProcessor.doExecute(records);

            verify(mlBatchJobCreator, times(1)).addProcessedBatchRecordsToResults(new ArrayList<>());
            verify(mlBatchJobCreator, times(1)).createMLBatchJob(records, new ArrayList<>());
            verify(successCounter, times(1)).increment();
        }

        @Test
        void testDoExecute_WithNoRecords() {
            final Collection<Record<Event>> result = mlProcessor.doExecute(Collections.emptyList());

            verifyNoInteractions(successCounter, failureCounter);
            assertTrue(result.isEmpty());
        }

        @Test
        void testDoExecute_WithConditionNotMet() {
            final Event event = mock(Event.class);
            final Record<Event> record = new Record<>(event);
            final List<Record<Event>> records = Collections.singletonList(record);

            when(expressionEvaluator.evaluateConditional(eq("condition"), any())).thenReturn(false);

            final Collection<Record<Event>> result = mlProcessor.doExecute(records);

            verify(mlBatchJobCreator, times(1)).addProcessedBatchRecordsToResults(records);
            verify(mlBatchJobCreator, times(1)).checkAndProcessBatch();
            verifyNoInteractions(successCounter, failureCounter);
            assertEquals(records, result);
        }

        @Test
        void testDoExecute_WithException() throws Exception {
            final Event event = mock(Event.class);
            final Record<Event> record = new Record<>(event);
            final List<Record<Event>> records = Collections.singletonList(record);

            doThrow(new RuntimeException("Test Exception")).when(mlBatchJobCreator).createMLBatchJob(records, new ArrayList<>());

            mlProcessor.doExecute(records);

            verify(failureCounter, times(1)).increment();
        }

        @Test
        void testShutdownMethods() {
            when(mlBatchJobCreator.isReadyForShutdown()).thenReturn(true);

            assertTrue(mlProcessor.isReadyForShutdown());
            mlProcessor.prepareForShutdown();
            mlProcessor.shutdown();

            verify(mlBatchJobCreator).isReadyForShutdown();
            verify(mlBatchJobCreator).prepareForShutdown();
            verify(mlBatchJobCreator).shutdown();
        }
    }

    @Nested
    class PredictMode {

        private MLProcessor mlProcessor;
        private ModelSyncInferenceExecutor modelSyncInferenceExecutor;

        @BeforeEach
        void setUp() throws NoSuchFieldException, IllegalAccessException {
            modelSyncInferenceExecutor = mock(ModelSyncInferenceExecutor.class);
            setupCommonMocks();
            when(mlProcessorConfig.getWhenCondition()).thenReturn("condition");
            lenient().when(expressionEvaluator.evaluateConditional(eq("condition"), any())).thenReturn(true);
            when(mlProcessorConfig.getActionType()).thenReturn(ActionType.PREDICT);
            // provide a valid built-in model ID so the ModelSyncInferenceExecutor constructor can resolve the connector
            lenient().when(mlProcessorConfig.getModelId()).thenReturn("amazon.titan-embed-text-v2:0");
            lenient().when(mlProcessorConfig.getTagsOnFailure()).thenReturn(Collections.emptyList());

            mlProcessor = new MLProcessor(mlProcessorConfig, pluginMetrics, pluginFactory, pluginSetting, awsCredentialsSupplier, expressionEvaluator);
            Field field = MLProcessor.class.getDeclaredField("modelSyncInferenceExecutor");
            field.setAccessible(true);
            field.set(mlProcessor, modelSyncInferenceExecutor);
        }

        @Test
        void testDoExecute_WithValidRecords_delegatesToExecutor() {
            final Event event = mock(Event.class);
            final Record<Event> record = new Record<>(event);
            final List<Record<Event>> records = Collections.singletonList(record);
            when(modelSyncInferenceExecutor.execute(records)).thenReturn(records);

            final Collection<Record<Event>> result = mlProcessor.doExecute(records);

            verify(modelSyncInferenceExecutor, times(1)).execute(records);
            verify(successCounter, times(1)).increment();
            assertEquals(records, result);
        }

        @Test
        void testDoExecute_WithNoRecords_returnsEmpty() {
            final Collection<Record<Event>> result = mlProcessor.doExecute(Collections.emptyList());

            verifyNoInteractions(modelSyncInferenceExecutor, successCounter, failureCounter);
            assertTrue(result.isEmpty());
        }

        @Test
        void testDoExecute_WithConditionNotMet_skipsExecutor() {
            final Event event = mock(Event.class);
            final Record<Event> record = new Record<>(event);
            final List<Record<Event>> records = Collections.singletonList(record);
            when(expressionEvaluator.evaluateConditional(eq("condition"), any())).thenReturn(false);

            final Collection<Record<Event>> result = mlProcessor.doExecute(records);

            verifyNoInteractions(modelSyncInferenceExecutor, successCounter, failureCounter);
            assertEquals(records, result);
        }

        @Test
        void testDoExecute_WithConditionMet_passesFilteredRecordsToExecutor() {
            final Event matchedEvent = mock(Event.class);
            final Event skippedEvent = mock(Event.class);
            final Record<Event> matchedRecord = new Record<>(matchedEvent);
            final Record<Event> skippedRecord = new Record<>(skippedEvent);
            final List<Record<Event>> records = List.of(matchedRecord, skippedRecord);

            when(expressionEvaluator.evaluateConditional(eq("condition"), eq(matchedEvent))).thenReturn(true);
            when(expressionEvaluator.evaluateConditional(eq("condition"), eq(skippedEvent))).thenReturn(false);

            final List<Record<Event>> filteredRecords = Collections.singletonList(matchedRecord);
            when(modelSyncInferenceExecutor.execute(filteredRecords)).thenReturn(filteredRecords);

            final Collection<Record<Event>> result = mlProcessor.doExecute(records);

            verify(modelSyncInferenceExecutor, times(1)).execute(filteredRecords);
            assertTrue(result.contains(matchedRecord));
            assertTrue(result.contains(skippedRecord));
            assertEquals(2, result.size());
        }

        @Test
        void testDoExecute_ExecutorThrows_incrementsFailureCounter() {
            final Event event = mock(Event.class);
            final Record<Event> record = new Record<>(event);
            final List<Record<Event>> records = Collections.singletonList(record);
            when(modelSyncInferenceExecutor.execute(any())).thenThrow(new RuntimeException("predict failed"));

            mlProcessor.doExecute(records);

            verify(failureCounter, times(1)).increment();
            verifyNoInteractions(successCounter);
        }

        @Test
        void testIsReadyForShutdown_returnsTrueWhenNoBatchJobCreator() {
            assertTrue(mlProcessor.isReadyForShutdown());
        }

        @Test
        void testShutdownMethods_areNoOpsWhenNoBatchJobCreator() {
            mlProcessor.prepareForShutdown();
            mlProcessor.shutdown();
        }
    }
}
