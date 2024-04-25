/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import static io.airbyte.metrics.lib.ApmTraceConstants.ACTIVITY_TRACE_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.CONNECTION_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.SOURCE_ID_KEY;

import datadog.trace.api.Trace;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.model.generated.ActorCatalogWithUpdatedAt;
import io.airbyte.api.client.model.generated.ConnectionAutoPropagateSchemaChange;
import io.airbyte.api.client.model.generated.ConnectionIdRequestBody;
import io.airbyte.api.client.model.generated.SourceAutoPropagateChange;
import io.airbyte.api.client.model.generated.SourceDiscoverSchemaRead;
import io.airbyte.api.client.model.generated.SourceDiscoverSchemaRequestBody;
import io.airbyte.api.client.model.generated.SourceIdRequestBody;
import io.airbyte.api.client.model.generated.WorkloadPriority;
import io.airbyte.commons.features.FeatureFlags;
import io.airbyte.commons.temporal.utils.PayloadChecker;
import io.airbyte.featureflag.AutoBackfillOnNewColumns;
import io.airbyte.featureflag.Connection;
import io.airbyte.featureflag.Context;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.Multi;
import io.airbyte.featureflag.RefreshSchemaPeriod;
import io.airbyte.featureflag.ShouldRunRefreshSchema;
import io.airbyte.featureflag.SourceDefinition;
import io.airbyte.featureflag.Workspace;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.metrics.lib.MetricAttribute;
import io.airbyte.metrics.lib.MetricClientFactory;
import io.airbyte.metrics.lib.MetricTags;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.workers.models.RefreshSchemaActivityInput;
import io.airbyte.workers.models.RefreshSchemaActivityOutput;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Refresh schema temporal activity impl.
 */
@Slf4j
@Singleton
public class RefreshSchemaActivityImpl implements RefreshSchemaActivity {

  private final AirbyteApiClient airbyteApiClient;
  private final FeatureFlags envVariableFeatureFlags;
  private final FeatureFlagClient featureFlagClient;
  private final PayloadChecker payloadChecker;

  public RefreshSchemaActivityImpl(final AirbyteApiClient airbyteApiClient,
                                   final FeatureFlags envVariableFeatureFlags,
                                   final FeatureFlagClient featureFlagClient,
                                   final PayloadChecker payloadChecker) {
    this.airbyteApiClient = airbyteApiClient;
    this.envVariableFeatureFlags = envVariableFeatureFlags;
    this.featureFlagClient = featureFlagClient;
    this.payloadChecker = payloadChecker;
  }

  @Override
  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  public boolean shouldRefreshSchema(final UUID sourceCatalogId) {
    if (!envVariableFeatureFlags.autoDetectSchema()) {
      return false;
    }

    ApmTraceUtils.addTagsToTrace(Map.of(SOURCE_ID_KEY, sourceCatalogId));
    return !schemaRefreshRanRecently(sourceCatalogId);
  }

  private SourceDiscoverSchemaRead discoverSchemaForRefresh(final UUID sourceId, final UUID connectionId) throws Exception {
    if (!envVariableFeatureFlags.autoDetectSchema()) {
      return null;
    }

    final UUID sourceDefinitionId =
        AirbyteApiClient.retryWithJitterThrows(
            () -> airbyteApiClient.getSourceApi().getSource(new SourceIdRequestBody().sourceId(sourceId)).getSourceDefinitionId(),
            "Get the source definition id by source id");

    final List<Context> featureFlagContexts = List.of(new SourceDefinition(sourceDefinitionId), new Connection(connectionId));
    if (!featureFlagClient.boolVariation(ShouldRunRefreshSchema.INSTANCE, new Multi(featureFlagContexts))) {
      return null;
    }
    MetricClientFactory.getMetricClient().count(OssMetricsRegistry.ACTIVITY_REFRESH_SCHEMA, 1);

    ApmTraceUtils.addTagsToTrace(Map.of(CONNECTION_ID_KEY, connectionId, SOURCE_ID_KEY, sourceId));

    final SourceDiscoverSchemaRequestBody requestBody =
        new SourceDiscoverSchemaRequestBody().sourceId(sourceId).disableCache(true).connectionId(connectionId).notifySchemaChange(true)
            .priority(WorkloadPriority.DEFAULT);

    return AirbyteApiClient.retryWithJitterThrows(
        () -> airbyteApiClient.getSourceApi().discoverSchemaForSource(requestBody),
        "Trigger discover schema");
  }

  @Override
  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  public void refreshSchema(final UUID sourceId, final UUID connectionId) throws Exception {
    final var sourceDiscoverSchemaRead = discoverSchemaForRefresh(sourceId, connectionId);
    if (sourceDiscoverSchemaRead == null) {
      return;
    }
    if (!sourceDiscoverSchemaRead.getJobInfo().getSucceeded()) {
      log.warn("Failed to refresh schema; proceeding with sync.");
      return;
    }
    final UUID workspaceId = AirbyteApiClient.retryWithJitterThrows(
        () -> airbyteApiClient.getWorkspaceApi().getWorkspaceByConnectionId(new ConnectionIdRequestBody().connectionId(connectionId))
            .getWorkspaceId(),
        "Get the workspace by connection Id");

    final SourceAutoPropagateChange sourceAutoPropagateChange = new SourceAutoPropagateChange()
        .sourceId(sourceId)
        .catalog(sourceDiscoverSchemaRead.getCatalog())
        .workspaceId(workspaceId)
        .catalogId(sourceDiscoverSchemaRead.getCatalogId());

    AirbyteApiClient.retryWithJitterThrows(
        () -> {
          airbyteApiClient.getSourceApi().applySchemaChangeForSource(sourceAutoPropagateChange);
          return null;
        },
        "Auto propagate the schema change");
  }

  @Override
  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  public RefreshSchemaActivityOutput refreshSchemaV2(final RefreshSchemaActivityInput input) throws Exception {
    final var workspaceId = input.getWorkspaceId();
    final var sourceId = input.getSourceCatalogId();
    final var connectionId = input.getConnectionId();
    if (!featureFlagClient.boolVariation(AutoBackfillOnNewColumns.INSTANCE, new Workspace(workspaceId))) {
      // If we don't want to backfill on new columns, fall back to the original schema refresh.
      refreshSchema(sourceId, connectionId);
      return new RefreshSchemaActivityOutput(null);
    }
    final var sourceDiscoverSchemaRead = discoverSchemaForRefresh(sourceId, connectionId);
    if (sourceDiscoverSchemaRead == null) {
      return new RefreshSchemaActivityOutput(null);
    }
    if (!sourceDiscoverSchemaRead.getJobInfo().getSucceeded()) {
      log.warn("Failed to refresh schema; proceeding with sync.");
      return new RefreshSchemaActivityOutput(null);
    }

    final ConnectionAutoPropagateSchemaChange request = new ConnectionAutoPropagateSchemaChange()
        .catalog(sourceDiscoverSchemaRead.getCatalog())
        .workspaceId(workspaceId)
        .connectionId(connectionId)
        .catalogId(sourceDiscoverSchemaRead.getCatalogId());

    final var output = new RefreshSchemaActivityOutput(AirbyteApiClient.retryWithJitterThrows(
        () -> airbyteApiClient.getConnectionApi().applySchemaChangeForConnection(request),
        "Auto propagate the schema change").getPropagatedDiff());

    final var attrs = new MetricAttribute[] {
      new MetricAttribute(MetricTags.CONNECTION_ID, String.valueOf(connectionId))
    };

    payloadChecker.validatePayloadSize(output, attrs);

    return output;
  }

  private boolean schemaRefreshRanRecently(final UUID sourceCatalogId) {
    try {
      final SourceIdRequestBody sourceIdRequestBody = new SourceIdRequestBody().sourceId(sourceCatalogId);
      final ActorCatalogWithUpdatedAt mostRecentFetchEvent = AirbyteApiClient.retryWithJitter(
          () -> airbyteApiClient.getSourceApi().getMostRecentSourceActorCatalog(sourceIdRequestBody),
          "get the most recent source actor catalog");
      if (mostRecentFetchEvent.getUpdatedAt() == null) {
        return false;
      }
      final UUID workspaceId = AirbyteApiClient.retryWithJitter(
          () -> airbyteApiClient.getSourceApi().getSource(sourceIdRequestBody).getWorkspaceId(),
          "Retrieve Id of the workspace for the source");
      int refreshPeriod = 24;
      if (workspaceId != null) {
        refreshPeriod = featureFlagClient.intVariation(RefreshSchemaPeriod.INSTANCE, new Workspace(workspaceId));
      }
      return mostRecentFetchEvent.getUpdatedAt() > OffsetDateTime.now().minusHours(refreshPeriod).toEpochSecond();
    } catch (final Exception e) {
      ApmTraceUtils.addExceptionToTrace(e);
      // catching this exception because we don't want to block replication due to a failed schema refresh
      log.info("Encountered an error fetching most recent actor catalog fetch event: ", e);
      return true;
    }
  }

}
