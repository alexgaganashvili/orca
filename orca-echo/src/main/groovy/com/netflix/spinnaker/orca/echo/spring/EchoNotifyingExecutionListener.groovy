/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.orca.echo.spring

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.api.ExecutionStatus
import com.netflix.spinnaker.orca.api.PipelineExecution
import com.netflix.spinnaker.orca.echo.EchoService
import com.netflix.spinnaker.orca.front50.Front50Service
import com.netflix.spinnaker.orca.front50.model.ApplicationNotifications
import com.netflix.spinnaker.orca.listeners.ExecutionListener
import com.netflix.spinnaker.orca.listeners.Persister
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.security.AuthenticatedRequest
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import static com.netflix.spinnaker.orca.api.ExecutionType.PIPELINE

@Slf4j
@CompileStatic
class EchoNotifyingExecutionListener implements ExecutionListener {

  private final EchoService echoService
  private final Front50Service front50Service
  private final ObjectMapper objectMapper
  private final ContextParameterProcessor contextParameterProcessor

  EchoNotifyingExecutionListener(
    EchoService echoService,
    Front50Service front50Service,
    ObjectMapper objectMapper,
    ContextParameterProcessor contextParameterProcessor) {
    this.echoService = echoService
    this.front50Service = front50Service
    this.objectMapper = objectMapper
    this.contextParameterProcessor = contextParameterProcessor
  }

  @Override
  void beforeExecution(Persister persister, PipelineExecution execution) {
    try {
      if (execution.status != ExecutionStatus.SUSPENDED) {
        processSpelInNotifications(execution)

        if (execution.type == PIPELINE) {
          addApplicationNotifications(execution)
        }
        AuthenticatedRequest.allowAnonymous({
          echoService.recordEvent(
            details: [
              source     : "orca",
              type       : "orca:${execution.type}:starting".toString(),
              application: execution.application,
            ],
            content: buildContent(execution)
          )
        })
      }
    } catch (Exception e) {
      log.error("Failed to send pipeline start event: ${execution?.id}", e)
    }
  }

  @Override
  void afterExecution(Persister persister,
                      PipelineExecution execution,
                      ExecutionStatus executionStatus,
                      boolean wasSuccessful) {
    try {
      if (execution.status != ExecutionStatus.SUSPENDED) {
        processSpelInNotifications(execution)

        if (execution.type == PIPELINE) {
          addApplicationNotifications(execution)
        }
        AuthenticatedRequest.allowAnonymous({
          echoService.recordEvent(
            details: [
              source     : "orca",
              type       : "orca:${execution.type}:${wasSuccessful ? "complete" : "failed"}".toString(),
              application: execution.application,
            ],
            content: buildContent(execution)
          )
        })
      }
    } catch (Exception e) {
      log.error("Failed to send pipeline end event: ${execution?.id}", e)
    }
  }

  private void processSpelInNotifications(PipelineExecutionImpl execution) {
    List<Map<String, Object>> spelProcessedNotifications = execution.notifications.collect({
      contextParameterProcessor.process(it, contextParameterProcessor.buildExecutionContext(execution), true)
    })

    execution.notifications = spelProcessedNotifications
  }

  /**
   * Adds any application-level notifications to the pipeline's notifications
   * If a notification exists on both with the same address and type, the pipeline's notification will be treated as an
   * override, and any "when" values in the application-level notification that are also in the pipeline's notification
   * will be removed from the application-level notification
   *
   * @param pipeline
   */
  private void addApplicationNotifications(PipelineExecutionImpl pipeline) {
    def user = PipelineExecutionImpl.AuthenticationHelper.toKorkUser(pipeline.getAuthentication())
    ApplicationNotifications notifications
    if (user?.isPresent()) {
      notifications = AuthenticatedRequest.propagate({
        front50Service.getApplicationNotifications(pipeline.application)
      }, user.get()).call()
    } else {
      notifications = AuthenticatedRequest.allowAnonymous({
        front50Service.getApplicationNotifications(pipeline.application)
      })
    }

    if (notifications) {
      notifications.getPipelineNotifications().each { appNotification ->
        appNotification = contextParameterProcessor.process(appNotification, contextParameterProcessor.buildExecutionContext(pipeline), true)

        Map<String, Object> targetMatch = pipeline.notifications.find { pipelineNotification ->
          def addressMatches = appNotification.address && pipelineNotification.address && pipelineNotification.address == appNotification.address
          def publisherMatches = appNotification.publisherName && pipelineNotification.publisherName && pipelineNotification.publisherName == appNotification.publisherName
          def typeMatches = appNotification.type && pipelineNotification.type && pipelineNotification.type == appNotification.type

          return (addressMatches || publisherMatches) && typeMatches
        }
        if (!targetMatch) {
          pipeline.notifications.push(appNotification)
        } else {
          Collection<String> appWhen = ((Collection<String>) appNotification.when)
          Collection<String> pipelineWhen = (Collection<String>) targetMatch.when
          appWhen.removeAll(pipelineWhen)
          if (!appWhen.isEmpty()) {
            pipeline.notifications.push(appNotification)
          }
        }
      }
    }
  }

  private Map<String, Object> buildContent(PipelineExecutionImpl execution) {
    return contextParameterProcessor.process(
      [
        execution: execution,
        executionId: execution.id
      ] as Map<String, Object>,
      [execution: execution] as Map<String, Object>,
      true
    )
  }
}
