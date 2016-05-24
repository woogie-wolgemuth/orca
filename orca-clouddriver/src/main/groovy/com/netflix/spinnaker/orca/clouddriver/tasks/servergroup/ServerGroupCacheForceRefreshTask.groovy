/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.servergroup

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.DefaultTaskResult
import com.netflix.spinnaker.orca.RetryableTask
import com.netflix.spinnaker.orca.TaskResult
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.clouddriver.tasks.AbstractCloudProviderAwareTask
import com.netflix.spinnaker.orca.pipeline.model.Stage
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.time.Clock
import java.util.concurrent.TimeUnit

import static com.netflix.spinnaker.orca.ExecutionStatus.*

@Component
@Slf4j
class ServerGroupCacheForceRefreshTask extends AbstractCloudProviderAwareTask implements RetryableTask {
  static final String REFRESH_TYPE = "ServerGroup"

  long backoffPeriod = TimeUnit.SECONDS.toMillis(5)
  long timeout = TimeUnit.MINUTES.toMillis(15)

  long autoSucceedAfterMs = TimeUnit.MINUTES.toMillis(12)

  @Autowired
  OortService oort

  @Autowired
  ObjectMapper objectMapper

  Clock clock = Clock.systemUTC()

  @Override
  TaskResult execute(Stage stage) {
    if ((clock.millis() - stage.startTime) > autoSucceedAfterMs) {
      /*
       * If an issue arises performing a refresh, wait at least 10 minutes (the default ttl of a cache record) before
       * short-circuiting and succeeding.
       *
       * Under normal operations, this refresh task should complete sub-minute.
       */
      return new DefaultTaskResult(SUCCEEDED, ["shortCircuit": true])
    }

    def account = getCredentials(stage)
    def cloudProvider = getCloudProvider(stage)
    def stageData = stage.mapTo(StageData)

    def optionalTaskResult = performForceCacheRefresh(account, cloudProvider, stageData)
    if (optionalTaskResult.present) {
      return optionalTaskResult.get()
    }

    boolean allAreComplete = processPendingForceCacheUpdates(account, cloudProvider, stageData, stage.startTime)
    if (allAreComplete) {
      // ensure clean stage data such that a subsequent ServerGroupCacheForceRefresh (in this stage) starts fresh
      stageData.reset()
    }

    return new DefaultTaskResult(allAreComplete ? SUCCEEDED : RUNNING, convertAndStripNullValues(stageData))
  }

  /**
   * Every deployed server group should be force cache refreshed.
   *
   * An HTTP 200 response indicates that the force cache operation has completed and there is no need for additional
   * polling. Long term, the expectation is that all caching agents will be asynchronous.
   *
   * An HTTP 202 response indicates that the force cache operation has been queued and will complete at some point in
   * the near future.
   */
  private Optional<TaskResult> performForceCacheRefresh(String account, String cloudProvider, StageData stageData) {
    def zone = stageData.zone

    def refreshableServerGroups = stageData.deployServerGroups.collect { region, serverGroups ->
      serverGroups.findResults { String serverGroup ->
        def model = [asgName: serverGroup, serverGroupName: serverGroup, region: region, account: account]
        if (zone) {
          model.zone = zone
        }

        return !stageData.refreshedServerGroups.contains(model) ? model : null
      }
    }.flatten()

    if (!refreshableServerGroups) {
      return Optional.empty()
    }

    def status = RUNNING
    refreshableServerGroups.each { Map<String, String> model ->
      try {
        def response = oort.forceCacheUpdate(cloudProvider, REFRESH_TYPE, model)
        if (response.status == HttpURLConnection.HTTP_OK) {
          // cache update was applied immediately, no need to poll for completion
          status = SUCCEEDED
        }

        stageData.refreshedServerGroups << model
      } catch (e) {
        stageData.errors << e.message
      }
    }

    return Optional.of(new DefaultTaskResult(status, convertAndStripNullValues(stageData)))
  }

  /**
   * Ensure that:
   * - We see a pending force cache update for every deployed server group
   * - The pending force cache update is recent (newer than the start of this particular stage)
   * - The pending force cache update has been processed (ie. it's survived one full pass of the caching agent)
   *
   * It's possible waiting until processing is overkill but we do this to avoid the possibility of a race condition
   * between a forceCache refresh and an ongoing caching agent cycle.
   */
  private boolean processPendingForceCacheUpdates(String account,
                                                  String cloudProvider,
                                                  StageData stageData,
                                                  Long startTime) {
    def pendingForceCacheUpdates = oort.pendingForceCacheUpdates(cloudProvider, REFRESH_TYPE)

    boolean finishedProcessing = true
    stageData.deployServerGroups.each { String region, Set<String> serverGroups ->
      def makeModel = { serverGroup ->
        [serverGroup: serverGroup, region: region, account: account]
      }

      def processedServerGroups = serverGroups.findAll { String serverGroup ->
        def model = makeModel(serverGroup)

        if (stageData.processedServerGroups.contains(model)) {
          // this server group has already been processed
          return true
        }

        def forceCacheUpdate = pendingForceCacheUpdates.find {
          (it.details as Map<String, String>).intersect(model) == model
        }

        if (!forceCacheUpdate) {
          // there is no pending cache update, force it again in the event that it was missed
          stageData.removeRefreshedServerGroup(model.serverGroup, model.region, model.account)
          log.warn("Unable to find pending cache refresh request (model: ${model})")

          return false
        }

        if (forceCacheUpdate) {
          if (!forceCacheUpdate.processedTime) {
            // there is a pending cache update that is still awaiting processing
            log.warn("Awaiting processing on pending cache refresh request (model: ${model})")

            return false
          }

          if (forceCacheUpdate.processedTime < startTime || forceCacheUpdate.cacheTime < startTime) {
            // there is a stale pending cache update, force it again
            stageData.removeRefreshedServerGroup(serverGroup, region, account)
            log.warn("Found stale pending cache refresh request (model: ${model}, request: ${forceCacheUpdate})")

            return false
          }
        }

        return true
      }

      stageData.processedServerGroups.addAll(processedServerGroups.collect {
        makeModel(it)
      })

      finishedProcessing = finishedProcessing && (processedServerGroups == serverGroups)
    }
    return finishedProcessing
  }

  private Map convertAndStripNullValues(StageData stageData) {
    def result = objectMapper.convertValue(stageData, Map)

    result.values().removeAll { it == null }

    return result
  }

  static class StageData {
    @JsonProperty("deploy.server.groups")
    Map<String, Set<String>> deployServerGroups = [:]

    @JsonProperty("refreshed.server.groups")
    Set<Map> refreshedServerGroups = []

    @JsonProperty("processed.server.groups")
    Set<Map> processedServerGroups = []

    @JsonProperty("force.cache.refresh.errors")
    Collection<String> errors = []

    Collection<String> zones = []
    String zone

    String getZone() {
      return this.zone ?: (zones ? zones[0] : null)
    }

    void removeRefreshedServerGroup(String serverGroupName, String region, String account) {
      refreshedServerGroups.remove(
        refreshedServerGroups.find {
          it.serverGroupName == serverGroupName && it.region == region && it.account == account
        }
      )
    }

    void reset() {
      refreshedServerGroups = []
      processedServerGroups = []
      errors = []
    }
  }
}