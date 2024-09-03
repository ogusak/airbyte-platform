package io.airbyte.workload.launcher.pods

import com.google.common.annotations.VisibleForTesting
import datadog.trace.api.Trace
import io.airbyte.commons.constants.WorkerConstants.KubeConstants.FULL_POD_TIMEOUT
import io.airbyte.config.ResourceRequirements
import io.airbyte.featureflag.Connection
import io.airbyte.featureflag.ConnectorSidecarFetchesInputFromInit
import io.airbyte.featureflag.Context
import io.airbyte.featureflag.FeatureFlagClient
import io.airbyte.featureflag.Multi
import io.airbyte.featureflag.ReplicationMonoPod
import io.airbyte.featureflag.ReplicationMonoPodMemoryTolerance
import io.airbyte.featureflag.Workspace
import io.airbyte.metrics.lib.ApmTraceUtils
import io.airbyte.persistence.job.models.ReplicationInput
import io.airbyte.workers.input.getDestinationResourceReqs
import io.airbyte.workers.input.getOrchestratorResourceReqs
import io.airbyte.workers.input.getSourceResourceReqs
import io.airbyte.workers.input.setDestinationLabels
import io.airbyte.workers.input.setSourceLabels
import io.airbyte.workers.models.CheckConnectionInput
import io.airbyte.workers.models.DiscoverCatalogInput
import io.airbyte.workers.models.SpecInput
import io.airbyte.workers.pod.PodLabeler
import io.airbyte.workers.process.KubePodProcess
import io.airbyte.workload.launcher.metrics.MeterFilterFactory.Companion.LAUNCH_REPLICATION_OPERATION_NAME
import io.airbyte.workload.launcher.metrics.MeterFilterFactory.Companion.WAIT_DESTINATION_OPERATION_NAME
import io.airbyte.workload.launcher.metrics.MeterFilterFactory.Companion.WAIT_ORCHESTRATOR_OPERATION_NAME
import io.airbyte.workload.launcher.metrics.MeterFilterFactory.Companion.WAIT_SOURCE_OPERATION_NAME
import io.airbyte.workload.launcher.pipeline.consumer.LauncherInput
import io.airbyte.workload.launcher.pods.factories.ConnectorPodFactory
import io.airbyte.workload.launcher.pods.factories.OrchestratorPodFactory
import io.airbyte.workload.launcher.pods.factories.ReplicationPodFactory
import io.fabric8.kubernetes.api.model.Pod
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.time.TimeSource

/**
 * Interface layer between domain and Kube layers.
 * Composes raw Kube layer atomic operations to perform business operations.
 */
@Singleton
class KubePodClient(
  private val kubePodLauncher: KubePodLauncher,
  private val labeler: PodLabeler,
  private val mapper: PayloadKubeInputMapper,
  private val orchestratorPodFactory: OrchestratorPodFactory,
  private val replicationPodFactory: ReplicationPodFactory,
  @Named("checkPodFactory") private val checkPodFactory: ConnectorPodFactory,
  @Named("discoverPodFactory") private val discoverPodFactory: ConnectorPodFactory,
  @Named("specPodFactory") private val specPodFactory: ConnectorPodFactory,
  private val featureFlagClient: FeatureFlagClient,
  @Named("infraFlagContexts") private val contexts: List<Context>,
) {
  fun podsExistForAutoId(autoId: UUID): Boolean {
    return kubePodLauncher.podsRunning(labeler.getAutoIdLabels(autoId))
  }

  @Trace(operationName = LAUNCH_REPLICATION_OPERATION_NAME)
  fun launchReplication(
    replicationInput: ReplicationInput,
    launcherInput: LauncherInput,
  ) {
    if (useMonoPod(replicationInput = replicationInput)) {
      launchReplicationMonoPod(replicationInput, launcherInput)
    } else {
      launchReplicationPodTriplet(replicationInput, launcherInput)
    }
  }

  fun launchReplicationPodTriplet(
    replicationInput: ReplicationInput,
    launcherInput: LauncherInput,
  ) {
    val sharedLabels = labeler.getSharedLabels(launcherInput.workloadId, launcherInput.mutexKey, launcherInput.labels, launcherInput.autoId)

    val inputWithLabels =
      replicationInput
        .setSourceLabels(sharedLabels)
        .setDestinationLabels(sharedLabels)

    val kubeInput = mapper.toKubeInput(launcherInput.workloadId, inputWithLabels, sharedLabels)

    var pod =
      orchestratorPodFactory.create(
        replicationInput.connectionId,
        kubeInput.orchestratorLabels,
        kubeInput.resourceReqs,
        kubeInput.nodeSelectors,
        kubeInput.kubePodInfo,
        kubeInput.annotations,
        kubeInput.extraEnv,
      )
    try {
      pod =
        kubePodLauncher.create(pod)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "Failed to create pod ${kubeInput.kubePodInfo.name}.",
        e,
        KubeCommandType.CREATE,
        PodType.ORCHESTRATOR,
      )
    }

    waitForPodInitComplete(pod, PodType.ORCHESTRATOR.toString())

    waitForOrchestratorStart(pod)

    // We wait for the destination first because orchestrator starts destinations first.
    waitDestinationReadyOrTerminalInit(kubeInput)

    if (!replicationInput.isReset) {
      waitSourceReadyOrTerminalInit(kubeInput)
    }
  }

  fun launchReplicationMonoPod(
    replicationInput: ReplicationInput,
    launcherInput: LauncherInput,
  ) {
    val sharedLabels = labeler.getSharedLabels(launcherInput.workloadId, launcherInput.mutexKey, launcherInput.labels, launcherInput.autoId)

    val kubeInput = mapper.toReplicationKubeInput(launcherInput.workloadId, replicationInput, sharedLabels)

    var pod =
      replicationPodFactory.create(
        kubeInput.podName,
        kubeInput.labels,
        kubeInput.annotations,
        kubeInput.nodeSelectors,
        kubeInput.orchestratorImage,
        kubeInput.sourceImage,
        kubeInput.destinationImage,
        kubeInput.orchestratorReqs,
        kubeInput.sourceReqs,
        kubeInput.destinationReqs,
        kubeInput.orchestratorRuntimeEnvVars,
        kubeInput.sourceRuntimeEnvVars,
        kubeInput.destinationRuntimeEnvVars,
        replicationInput.connectionId,
      )

    try {
      pod =
        kubePodLauncher.create(pod)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "Failed to create pod ${kubeInput.podName}.",
        e,
        KubeCommandType.CREATE,
        PodType.REPLICATION,
      )
    }

    // NOTE: might not be necessary depending on when `serversideApply` returns.
    // If it blocks until it moves from PENDING, then we are good. Otherwise, we
    // need this or something similar to wait for the pod to be running on the node.
    waitForPodInitComplete(pod, PodType.REPLICATION.toString())
  }

  @Trace(operationName = WAIT_ORCHESTRATOR_OPERATION_NAME)
  fun waitForOrchestratorStart(pod: Pod) {
    try {
      kubePodLauncher.waitForPodReadyOrTerminalByPod(pod, ORCHESTRATOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "Main container of orchestrator pod failed to start within allotted timeout of ${ORCHESTRATOR_STARTUP_TIMEOUT_VALUE.seconds} seconds. " +
          "(${e.message})",
        e,
        KubeCommandType.WAIT_MAIN,
        PodType.ORCHESTRATOR,
      )
    }
  }

  @Trace(operationName = WAIT_SOURCE_OPERATION_NAME)
  fun waitSourceReadyOrTerminalInit(kubeInput: OrchestratorKubeInput) {
    try {
      kubePodLauncher.waitForPodReadyOrTerminal(kubeInput.sourceLabels, REPL_CONNECTOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "Source pod failed to start within allotted timeout of ${REPL_CONNECTOR_STARTUP_TIMEOUT_VALUE.seconds} seconds. (${e.message})",
        e,
        KubeCommandType.WAIT_MAIN,
        PodType.SOURCE,
      )
    }
  }

  @Trace(operationName = WAIT_DESTINATION_OPERATION_NAME)
  fun waitDestinationReadyOrTerminalInit(kubeInput: OrchestratorKubeInput) {
    try {
      kubePodLauncher.waitForPodReadyOrTerminal(kubeInput.destinationLabels, REPL_CONNECTOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "Destination pod failed to start within allotted timeout of ${REPL_CONNECTOR_STARTUP_TIMEOUT_VALUE.seconds} seconds. (${e.message})",
        e,
        KubeCommandType.WAIT_MAIN,
        PodType.DESTINATION,
      )
    }
  }

  fun launchCheck(
    checkInput: CheckConnectionInput,
    launcherInput: LauncherInput,
  ) {
    // For check the workload id is too long to be store as a kube label thus it is not added
    val sharedLabels =
      labeler.getSharedLabels(
        workloadId = null,
        mutexKey = launcherInput.mutexKey,
        passThroughLabels = launcherInput.labels,
        autoId = launcherInput.autoId,
      )

    val kubeInput = mapper.toKubeInput(launcherInput.workloadId, checkInput, sharedLabels, launcherInput.logPath)

    launchConnectorWithSidecar(kubeInput, checkPodFactory, launcherInput.workloadType.toOperationName())
  }

  fun launchDiscover(
    discoverCatalogInput: DiscoverCatalogInput,
    launcherInput: LauncherInput,
  ) {
    // For discover the workload id is too long to be store as a kube label thus it is not added
    val sharedLabels =
      labeler.getSharedLabels(
        workloadId = null,
        mutexKey = launcherInput.mutexKey,
        passThroughLabels = launcherInput.labels,
        autoId = launcherInput.autoId,
      )

    val kubeInput = mapper.toKubeInput(launcherInput.workloadId, discoverCatalogInput, sharedLabels, launcherInput.logPath)

    launchConnectorWithSidecar(kubeInput, discoverPodFactory, launcherInput.workloadType.toOperationName())
  }

  fun launchSpec(
    specInput: SpecInput,
    launcherInput: LauncherInput,
  ) {
    // For spec the workload id is too long to be store as a kube label thus it is not added
    val sharedLabels =
      labeler.getSharedLabels(
        workloadId = null,
        mutexKey = launcherInput.mutexKey,
        passThroughLabels = launcherInput.labels,
        autoId = launcherInput.autoId,
      )

    val kubeInput = mapper.toKubeInput(launcherInput.workloadId, specInput, sharedLabels, launcherInput.logPath)

    launchConnectorWithSidecar(kubeInput, specPodFactory, launcherInput.workloadType.toOperationName())
  }

  @VisibleForTesting
  fun launchConnectorWithSidecar(
    kubeInput: ConnectorKubeInput,
    factory: ConnectorPodFactory,
    podLogLabel: String,
  ) {
    val start = TimeSource.Monotonic.markNow()

    // Whether we should kube cp init files over or let the init container fetch itself
    // if true the init container will fetch, if false we copy over the files
    // NOTE: FF must be equal for the factory calls and kube cp calls to avoid a potential race,
    // so we check the value here and pass it down.
    val ffContext =
      Multi(
        buildList {
          add(Workspace(kubeInput.workspaceId))
          addAll(contexts)
        },
      )
    val useFetchingInit = featureFlagClient.boolVariation(ConnectorSidecarFetchesInputFromInit, ffContext)

    var pod =
      factory.create(
        kubeInput.connectorLabels,
        kubeInput.nodeSelectors,
        kubeInput.kubePodInfo,
        kubeInput.annotations,
        kubeInput.extraEnv,
        useFetchingInit,
      )
    try {
      pod = kubePodLauncher.create(pod)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "Failed to create pod ${kubeInput.kubePodInfo.name}.",
        e,
        KubeCommandType.CREATE,
      )
    }

    if (!useFetchingInit) {
      try {
        kubePodLauncher.waitForPodInitStartup(pod, POD_INIT_TIMEOUT_VALUE)
      } catch (e: RuntimeException) {
        ApmTraceUtils.addExceptionToTrace(e)
        throw KubeClientException(
          "$podLogLabel pod failed to init within allotted timeout.",
          e,
          KubeCommandType.WAIT_INIT,
        )
      }

      try {
        kubePodLauncher.copyFilesToKubeConfigVolumeMain(pod, kubeInput.fileMap)
      } catch (e: RuntimeException) {
        ApmTraceUtils.addExceptionToTrace(e)
        throw KubeClientException(
          "Failed to copy files to $podLogLabel pod ${kubeInput.kubePodInfo.name}.",
          e,
          KubeCommandType.COPY,
        )
      }
    } else {
      waitForPodInitComplete(pod, podLogLabel)
    }

    try {
      kubePodLauncher.waitForPodReadyOrTerminalByPod(pod, REPL_CONNECTOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "$podLogLabel pod failed to start within allotted timeout.",
        e,
        KubeCommandType.WAIT_MAIN,
      )
    }

    println("ELAPSED TIME (SIDECAR): ${start.elapsedNow()}")
  }

  fun deleteMutexPods(mutexKey: String): Boolean {
    val labels = labeler.getMutexLabels(mutexKey)

    try {
      val deleted = kubePodLauncher.deleteActivePods(labels)

      return deleted.isNotEmpty()
    } catch (e: RuntimeException) {
      throw KubeClientException(
        "Failed to delete pods for mutex key: $mutexKey.",
        e,
        KubeCommandType.DELETE,
      )
    }
  }

  @VisibleForTesting
  fun waitForPodInitComplete(
    pod: Pod,
    podLogLabel: String,
  ) {
    try {
      kubePodLauncher.waitForPodInitComplete(pod, POD_INIT_TIMEOUT_VALUE)
    } catch (e: TimeoutException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubeClientException(
        "$podLogLabel pod failed to init within allotted timeout.",
        e,
        KubeCommandType.WAIT_INIT,
      )
    }
  }

  private fun useMonoPod(replicationInput: ReplicationInput): Boolean {
    val ffContext = Multi(listOf(Workspace(replicationInput.workspaceId), Connection(replicationInput.connectionId)))
    val memoryFootprint = getMemoryLimitTotal(replicationInput)
    return featureFlagClient.boolVariation(
      ReplicationMonoPod,
      ffContext,
    ) && featureFlagClient.intVariation(ReplicationMonoPodMemoryTolerance, ffContext) >= toGigabytes(memoryFootprint)
  }

  private fun getMemoryLimitTotal(replicationInput: ReplicationInput): Long {
    val sourceMemoryLimit = extractMemoryLimit(replicationInput.getSourceResourceReqs())
    val destinationMemoryLimit = extractMemoryLimit(replicationInput.getDestinationResourceReqs())
    val orchestratorMemoryLimit = extractMemoryLimit(replicationInput.getOrchestratorResourceReqs())
    return sourceMemoryLimit + destinationMemoryLimit + orchestratorMemoryLimit
  }

  private fun extractMemoryLimit(resourceRequirements: ResourceRequirements?): Long {
    return KubePodProcess.buildResourceRequirements(resourceRequirements)?.limits?.get("memory")?.numericalAmount?.toLong() ?: 0
  }

  companion object {
    private val TIMEOUT_SLACK: Duration = Duration.ofSeconds(5)
    val ORCHESTRATOR_STARTUP_TIMEOUT_VALUE: Duration = Duration.ofMinutes(2)
    val POD_INIT_TIMEOUT_VALUE: Duration = Duration.ofMinutes(15)
    val REPL_CONNECTOR_STARTUP_TIMEOUT_VALUE: Duration = FULL_POD_TIMEOUT.plus(TIMEOUT_SLACK)
    private const val BYTES_PER_GB = 1024 * 1024 * 1024

    private fun toGigabytes(bytes: Long): Int {
      return if (bytes > 0) {
        (bytes / BYTES_PER_GB).toInt()
      } else {
        bytes.toInt()
      }
    }
  }
}
