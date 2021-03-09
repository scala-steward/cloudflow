/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.operator.action

import akka.datap.crd.App
import akka.kube.actions.{ Action, CustomResourceAdapter }

import java.security.MessageDigest
import scala.collection.immutable._
import scala.util.{ Failure, Success, Try }
import com.typesafe.config._
import org.slf4j.LoggerFactory
import cloudflow.blueprint._
import cloudflow.blueprint.deployment.{ Topic, _ }
import cloudflow.operator.action.CloudflowApplication.PodStatus
import cloudflow.operator.action.CloudflowApplication.Status.log
import cloudflow.operator.action.Common.jsonToConfig
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.{
  ContainerState,
  HasMetadata,
  KubernetesResourceList,
  ObjectMetaBuilder,
  OwnerReference,
  OwnerReferenceBuilder,
  Pod
}
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.api.{ model => fabric8 }
import com.fasterxml.jackson.databind.{ JsonNode, PropertyNamingStrategy }
import com.fasterxml.jackson.databind.annotation.JsonNaming
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ MixedOperation, Resource }

import scala.jdk.CollectionConverters._

/**
 * CloudflowApplication Custom Resource.
 */
object CloudflowApplication {

  // TODO remove this class, rely on App.Cr, and keep just the methods for handling and transforming  the status

  implicit val adapter =
    CustomResourceAdapter[App.Cr, App.List](App.customResourceDefinitionContext)

  val PrometheusAgentKey = "prometheus"

  def apply(applicationSpec: App.Spec): App.Cr = {
    val metadata = new ObjectMetaBuilder()
      .withName(applicationSpec.appId)
      .withLabels(CloudflowLabels(applicationSpec.appId, applicationSpec.appVersion).baseLabels.asJava)
      .build()

    App.Cr(spec = applicationSpec, metadata = metadata)
  }

  // TODO: remove the need for this conversion
  def toCrStatus(status: Status): App.AppStatus = {
    Serialization.jsonMapper().readValue(Serialization.jsonMapper().writeValueAsString(status), classOf[App.AppStatus])
  }

  def getOwnerReference(app: App.Cr): OwnerReference =
    Util.getOwnerReference(app.getMetadata().getName(), app.getMetadata().getUid())

  def hash(applicationSpec: App.Spec): String = {
    // TODO: initialize the jsonMapper
    val jsonString = Serialization.jsonMapper().writeValueAsString(CloudflowApplication(applicationSpec))

    def bytesToHexString(bytes: Array[Byte]): String =
      bytes.map("%02x".format(_)).mkString
    val md = MessageDigest.getInstance("MD5")
    md.update(jsonString.getBytes("UTF8"))
    bytesToHexString(md.digest())
  }

  object Status {
    val Running = "Running"
    val Pending = "Pending"
    val CrashLoopBackOff = "CrashLoopBackOff"
    val Error = "Error"
    private val log = LoggerFactory.getLogger(Status.getClass)

    def apply(spec: App.Spec, runners: Map[String, Runner[_]]): Status = {
      val streamletStatuses = createStreamletStatuses(spec, runners)
      Status(spec.appId, spec.appVersion, streamletStatuses, Some(Pending))
    }

    def pendingAction(app: App.Cr, runners: Map[String, Runner[_]], msg: String): Action = {
      log.info(s"Setting pending status for app ${app.spec.appId}")
      Status(app.spec, runners)
        .copy(appStatus = Some(CloudflowApplication.Status.Pending), appMessage = Some(msg))
        .toAction(app)()
    }

    def errorAction(app: App.Cr, runners: Map[String, Runner[_]], msg: String): Action = {
      log.info(s"Setting error status for app ${app.spec.appId}")
      Status(app.spec, runners)
        .copy(
          appStatus = Some(CloudflowApplication.Status.Error),
          appMessage = Some(s"An unrecoverable error has occured, please undeploy the application. Reason: ${msg}"))
        .toAction(app)()
    }

    def createStreamletStatuses(spec: App.Spec, runners: Map[String, Runner[_]]) =
      spec.deployments.map { deployment =>
        val expectedPodCount = runners.get(deployment.runtime).map(_.expectedPodCount(deployment)).getOrElse(1)
        StreamletStatus(deployment.streamletName, expectedPodCount)
      }.toVector

    def calcAppStatus(streamletStatuses: Vector[StreamletStatus]): String =
      if (streamletStatuses.forall { streamletStatus =>
            streamletStatus.hasExpectedPods(streamletStatus.podStatuses.size) &&
            streamletStatus.podStatuses.forall(_.isReady)
          }) {
        Status.Running
      } else if (streamletStatuses.flatMap(_.podStatuses).exists(_.status == PodStatus.CrashLoopBackOff)) {
        Status.CrashLoopBackOff
      } else {
        Status.Pending
      }
  }

  // TODO: this is code repetition (it was done through ser/deser) should we use the CR in the blueprint?
  def fromCrDeploymentToBlueprint(deployment: App.Deployment): StreamletDeployment = {
    StreamletDeployment(
      name = deployment.name,
      runtime = deployment.runtime,
      image = deployment.image,
      streamletName = deployment.streamletName,
      className = deployment.className,
      endpoint = deployment.endpoint.flatMap { endpoint =>
        for {
          appId <- endpoint.appId
          streamlet <- endpoint.streamlet
          containerPort <- endpoint.containerPort
        } yield {
          Endpoint(appId = appId, streamlet = streamlet, containerPort = containerPort)
        }
      },
      secretName = deployment.secretName,
      config = jsonToConfig(deployment.config),
      portMappings = deployment.portMappings.map {
        case (k, v) =>
          k -> Topic(id = v.id, cluster = v.cluster, config = jsonToConfig(v.config))
      },
      volumeMounts = {
        if (deployment.volumeMounts == null || deployment.volumeMounts.isEmpty) None
        else
          Some(deployment.volumeMounts.map { vmd =>
            VolumeMountDescriptor(
              name = vmd.appId,
              path = vmd.path,
              accessMode = vmd.accessMode,
              pvcName = vmd.pvcName.getOrElse(""))
          }.toList)
      },
      replicas = deployment.replicas)
  }

  // the status is created with the expected number of streamlet statuses, derived from the CloudflowApplication.Spec, see companion
  @JsonNaming(classOf[PropertyNamingStrategy.SnakeCaseStrategy])
  case class Status(
      appId: String,
      appVersion: String,
      streamletStatuses: Vector[StreamletStatus],
      appStatus: Option[String],
      appMessage: Option[String] = None) {
    def aggregatedStatus = appStatus.getOrElse(Status.Pending)
    def updateApp(newApp: App.Cr, runners: Map[String, Runner[_]]) = {
      val newStreamletStatuses = Status.createStreamletStatuses(newApp.spec, runners).map { newStreamletStatus =>
        streamletStatuses
          .find(_.streamletName == newStreamletStatus.streamletName)
          .map { streamletStatus =>
            newStreamletStatus.copy(podStatuses = streamletStatus.podStatuses)
          }
          .getOrElse(newStreamletStatus)
      }
      copy(
        appId = newApp.spec.appId,
        appVersion = newApp.spec.appVersion,
        streamletStatuses = newStreamletStatuses,
        appStatus = Some(Status.calcAppStatus(newStreamletStatuses)))
    }
    def updatePod(streamletName: String, pod: Pod) = {
      val streamletStatus =
        streamletStatuses
          .find(_.streamletName == streamletName)
          .map(_.updatePod(pod))
          .toList
      val streamletStatusesUpdated = streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
      copy(
        streamletStatuses = streamletStatusesUpdated,
        appStatus = Some(Status.calcAppStatus(streamletStatusesUpdated)))
    }

    def deletePod(streamletName: String, pod: Pod) = {
      val streamletStatus =
        streamletStatuses
          .find(_.streamletName == streamletName)
          .map(_.deletePod(pod))
          .toList
      val streamletStatusesUpdated = streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
      copy(
        streamletStatuses = streamletStatusesUpdated,
        appStatus = Some(Status.calcAppStatus(streamletStatusesUpdated)))
    }

    def ignoreOnErrorStatus(oldApp: Option[App.Cr], newApp: App.Cr) = {
      val _ = newApp
      oldApp.map(_.status) match {
        case Some(status) if status.appStatus == Some(Status.Error) => false
        case _                                                      => true
      }
    }

    // TODO 60 looks a pretty high number! check why this is failing!
    def toAction(app: App.Cr)(retry: Int = 60): Action = {
      Action.operation[App.Cr, App.List, Try[App.Cr]](
        { client: KubernetesClient =>
          client
            .customResources(App.customResourceDefinitionContext, classOf[App.Cr], classOf[App.List])
        }, { cr: MixedOperation[App.Cr, App.List, Resource[App.Cr]] =>
          Try {
            val current = cr.inNamespace(app.namespace).withName(app.name)
            val res =
              Option(current.get()) match {
                case Some(curr) =>
                  curr.copy(status = toCrStatus(this))
                case _ =>
                  app.copy(status = toCrStatus(this))
              }
            current.updateStatus(res)
          }
        }, { res =>
          res match {
            case Success(_) => Action.noop
            case Failure(err) if retry > 0 =>
              log.error(s"Failure updating the CR status retries: $retry", err)
              toAction(app)(retry - 1)
            case Failure(err) =>
              log.error("Failure updating the CR status retries exhausted, giving up", err)
              throw err
          }
        })
    }
  }

  object StreamletStatus {
    def apply(streamletName: String, expectedPodCount: Int): StreamletStatus =
      StreamletStatus(streamletName, Some(expectedPodCount), Vector())
    def apply(streamletName: String, pod: Pod, expectedPodCount: Int): StreamletStatus =
      StreamletStatus(streamletName, Some(expectedPodCount), Vector(PodStatus(pod)))
    def apply(streamletName: String, expectedPodCount: Int, podStatuses: Vector[PodStatus]): StreamletStatus =
      StreamletStatus(streamletName, Some(expectedPodCount), podStatuses)
  }

  @JsonNaming(classOf[PropertyNamingStrategy.SnakeCaseStrategy])
  case class StreamletStatus(
      streamletName: String,
      // Added as Option for backwards compatibility
      expectedPodCount: Option[Int],
      podStatuses: Vector[PodStatus]) {
    def hasExpectedPods(nrOfPodsDetected: Int) = expectedPodCount.getOrElse(0) == nrOfPodsDetected
    def updatePod(pod: Pod) = {
      val podStatus = PodStatus(pod)
      copy(podStatuses = podStatuses.filterNot(_.name == podStatus.name) :+ podStatus)
    }
    def deletePod(pod: Pod) = copy(podStatuses = podStatuses.filterNot(_.name == pod.getMetadata().getName()))
  }

  object PodStatus {
    val Pending = "Pending"
    val Running = "Running"
    val Terminating = "Terminating"
    val Terminated = "Terminated"
    val Succeeded = "Succeeded"
    val Failed = "Failed"
    val Unknown = "Unknown"
    val CrashLoopBackOff = "CrashLoopBackOff"

    val ReadyTrue = "True"
    val ReadyFalse = "False"

    def apply(name: String, status: String, restarts: Int, nrOfContainersReady: Int, nrOfContainers: Int): PodStatus = {
      val ready = if (nrOfContainersReady == nrOfContainers && nrOfContainers > 0) ReadyTrue else ReadyFalse
      PodStatus(name, status, restarts, Some(nrOfContainersReady), Some(nrOfContainers), Some(ready))
    }
    def apply(name: String): PodStatus = PodStatus(name, Unknown)
    def apply(pod: Pod): PodStatus = {
      // See https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
      val name: String = pod.getMetadata().getName()
      val status: fabric8.PodStatus = Option(pod.getStatus()).getOrElse(new fabric8.PodStatus())
      val nrOfContainers: Int = Option(pod.getSpec())
        .map(_.getContainers().size())
        .getOrElse(status.getContainerStatuses.size())
      val nrOfContainersReady: Int = Try { status.getContainerStatuses.asScala.filter(_.getReady).size }.getOrElse(0)
      val restarts: Int = Try { status.getContainerStatuses.asScala.map(_.getRestartCount.intValue()).sum }.getOrElse(0)
      val containerStates = Try { status.getContainerStatuses.asScala.map(_.getState).toList }.getOrElse(List())

      // https://github.com/kubernetes/kubectl/blob/27fa797464ff6684ec591c46c69d5e013998a0d1/pkg/describe/describe.go#L698
      val st = if (pod.getMetadata.getDeletionTimestamp != null && pod.getMetadata.getDeletionTimestamp.nonEmpty) {
        Terminating
      } else {
        Try(status.getPhase).toOption match {
          case Some(PodStatus.Pending)   => getStatusFromContainerStates(containerStates, nrOfContainers)
          case Some(PodStatus.Running)   => getStatusFromContainerStates(containerStates, nrOfContainers)
          case Some(PodStatus.Succeeded) => Succeeded
          case Some(PodStatus.Failed)    => Failed
          case Some(PodStatus.Unknown)   => Unknown
          case _                         => getStatusFromContainerStates(containerStates, nrOfContainers)
        }
      }
      PodStatus(name, st, restarts, nrOfContainersReady, nrOfContainers)
    }

    private def getStatusFromContainerStates(containerStates: List[ContainerState], nrOfContainers: Int): String =
      if (containerStates.nonEmpty) {
        // - Running if all containers running;
        // - Terminated if all containers terminated;
        // - first Waiting reason found (ContainerCreating, CrashLoopBackOff, ErrImagePull, ...);
        // - otherwise Pending.
        if (containerStates.filter(_.getRunning != null).size == nrOfContainers) Running
        else if (containerStates.filter(_.getTerminated != null).size == nrOfContainers) Terminated
        else {
          containerStates
            .filter(_.getWaiting != null)
            .headOption
            .map(_.getWaiting.getReason)
            .getOrElse(Pending)
        }
      } else Pending
  }

  /**
   * Status of the pod.
   * ready can be "True", "False" or "Unknown"
   */
  @JsonNaming(classOf[PropertyNamingStrategy.SnakeCaseStrategy])
  case class PodStatus(
      name: String,
      status: String,
      restarts: Int = 0,
      // Optional for backwards compatibility
      nrOfContainersReady: Option[Int] = None,
      // Optional for backwards compatibility
      nrOfContainers: Option[Int] = None,
      // TODO can this be removed without breaking backwards-compatibility?
      ready: Option[String] = None) {
    def containers = nrOfContainers.getOrElse(0)
    def containersReady = nrOfContainersReady.getOrElse(0)
    def isReady = status == PodStatus.Running && containersReady == containers && containers > 0
  }
}

object StatusUpdate {
  private val log = LoggerFactory.getLogger(Status.getClass)

  object PodStatus {
    val Pending = "Pending"
    val Running = "Running"
    val Terminating = "Terminating"
    val Terminated = "Terminated"
    val Succeeded = "Succeeded"
    val Failed = "Failed"
    val Unknown = "Unknown"
    val CrashLoopBackOff = "CrashLoopBackOff"

    val ReadyTrue = "True"
    val ReadyFalse = "False"
  }

  private def podReady(ps: App.PodStatus) =
    ps.status == PodStatus.Running && ps.nrOfContainersReady == ps.nrOfContainers && ps.nrOfContainers > 0

  private def podStatus(
      name: String,
      status: String,
      restarts: Int,
      nrOfContainersReady: Int,
      nrOfContainers: Int): App.PodStatus = {
    val ready =
      if (nrOfContainersReady == nrOfContainers && nrOfContainers > 0) PodStatus.ReadyTrue else PodStatus.ReadyFalse
    App.PodStatus(
      name = name,
      status = status,
      restarts = restarts,
      nrOfContainersReady = nrOfContainersReady,
      nrOfContainers = nrOfContainers,
      ready = ready)
  }

  private def fromPod(pod: Pod): App.PodStatus = {
    // See https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
    val name: String = pod.getMetadata().getName()
    val status: fabric8.PodStatus = Option(pod.getStatus()).getOrElse(new fabric8.PodStatus())
    val nrOfContainers: Int = Try { // protect from null pointers
      Option(pod.getSpec())
        .map(_.getContainers().size())
        .getOrElse(status.getContainerStatuses.size())
    }.getOrElse(0)
    val nrOfContainersReady: Int = Try { status.getContainerStatuses.asScala.filter(_.getReady).size }.getOrElse(0)
    val restarts: Int = Try { status.getContainerStatuses.asScala.map(_.getRestartCount.intValue()).sum }.getOrElse(0)
    val containerStates = Try { status.getContainerStatuses.asScala.map(_.getState).toList }.getOrElse(List())

    // https://github.com/kubernetes/kubectl/blob/27fa797464ff6684ec591c46c69d5e013998a0d1/pkg/describe/describe.go#L698
    val st = if (pod.getMetadata.getDeletionTimestamp != null && pod.getMetadata.getDeletionTimestamp.nonEmpty) {
      PodStatus.Terminating
    } else {
      Try(status.getPhase).toOption match {
        case Some(PodStatus.Pending)   => getStatusFromContainerStates(containerStates, nrOfContainers)
        case Some(PodStatus.Running)   => getStatusFromContainerStates(containerStates, nrOfContainers)
        case Some(PodStatus.Succeeded) => PodStatus.Succeeded
        case Some(PodStatus.Failed)    => PodStatus.Failed
        case Some(PodStatus.Unknown)   => PodStatus.Unknown
        case _                         => getStatusFromContainerStates(containerStates, nrOfContainers)
      }
    }

    podStatus(
      name = name,
      status = st,
      restarts = restarts,
      nrOfContainersReady = nrOfContainersReady,
      nrOfContainers = nrOfContainers)
  }

  private def getStatusFromContainerStates(containerStates: List[ContainerState], nrOfContainers: Int): String =
    if (containerStates.nonEmpty) {
      // - Running if all containers running;
      // - Terminated if all containers terminated;
      // - first Waiting reason found (ContainerCreating, CrashLoopBackOff, ErrImagePull, ...);
      // - otherwise Pending.
      if (containerStates.filter(_.getRunning != null).size == nrOfContainers) PodStatus.Running
      else if (containerStates.filter(_.getTerminated != null).size == nrOfContainers) PodStatus.Terminated
      else {
        containerStates
          .filter(_.getWaiting != null)
          .headOption
          .map(_.getWaiting.getReason)
          .getOrElse(PodStatus.Pending)
      }
    } else PodStatus.Pending

  private def hasExpectedPods(streamlet: App.StreamletStatus)(nrOfPodsDetected: Int) =
    streamlet.expectedPodCount.getOrElse(0) == nrOfPodsDetected

  private def updatePod(streamlet: App.StreamletStatus)(pod: Pod) = {
    val podStatus = fromPod(pod)

    streamlet.copy(podStatuses = streamlet.podStatuses.filterNot(_.name == podStatus.name) :+ podStatus)
  }
  private def deletePod(streamlet: App.StreamletStatus)(pod: Pod): App.StreamletStatus = {
    streamlet.copy(podStatuses = streamlet.podStatuses.filterNot(_.name == pod.getMetadata().getName()))
  }

  object Status {
    val Running = "Running"
    val Pending = "Pending"
    val CrashLoopBackOff = "CrashLoopBackOff"
    val Error = "Error"
  }

  private def freshStatus(spec: App.Spec, runners: Map[String, Runner[_]]): App.AppStatus = {
    val streamletStatuses = createStreamletStatuses(spec, runners)
    App.AppStatus(
      appId = spec.appId,
      appVersion = spec.appVersion,
      appStatus = Status.Pending,
      appMessage = "",
      endpointStatuses = Nil,
      streamletStatuses = streamletStatuses)
  }

  def pendingAction(app: App.Cr, runners: Map[String, Runner[_]], msg: String): Action = {
    log.info(s"Setting pending status for app ${app.spec.appId}")
    val newStatus =
      freshStatus(app.spec, runners).copy(appStatus = CloudflowApplication.Status.Pending, appMessage = msg)
    val newApp = app.copy(status = newStatus)

    statusUpdateAction(newApp)()
  }

  def errorAction(app: App.Cr, runners: Map[String, Runner[_]], msg: String): Action = {
    log.info(s"Setting error status for app ${app.spec.appId}")
    val newStatus = freshStatus(app.spec, runners)
      .copy(
        appStatus = CloudflowApplication.Status.Error,
        appMessage = s"An unrecoverable error has occured, please undeploy the application. Reason: ${msg}")
    val newApp = app.copy(status = newStatus)

    statusUpdateAction(newApp)()
  }

  private def createStreamletStatuses(spec: App.Spec, runners: Map[String, Runner[_]]) =
    spec.deployments.map { deployment =>
      val expectedPodCount = runners.get(deployment.runtime).map(_.expectedPodCount(deployment)).getOrElse(1)
      App.StreamletStatus(
        streamletName = deployment.streamletName,
        expectedPodCount = Some(expectedPodCount),
        podStatuses = Nil)
    }.toVector

  private def calcAppStatus(streamletStatuses: Seq[App.StreamletStatus]): String =
    if (streamletStatuses.forall { streamletStatus =>
          hasExpectedPods(streamletStatus)(streamletStatus.podStatuses.size) &&
          streamletStatus.podStatuses.forall(podReady)
        }) {
      Status.Running
    } else if (streamletStatuses.flatMap(_.podStatuses).exists(_.status == PodStatus.CrashLoopBackOff)) {
      Status.CrashLoopBackOff
    } else {
      Status.Pending
    }

  private def aggregatedStatus(status: App.AppStatus) = {
    if (status.appStatus == null || status.appStatus.isEmpty) {
      Status.Pending
    } else {
      status.appStatus
    }
  }

  def updateApp(status: App.AppStatus)(newApp: App.Cr, runners: Map[String, Runner[_]]): App.AppStatus = {
    val newStreamletStatuses = createStreamletStatuses(newApp.spec, runners).map { newStreamletStatus =>
      status.streamletStatuses
        .find(_.streamletName == newStreamletStatus.streamletName)
        .map { streamletStatus =>
          newStreamletStatus.copy(podStatuses = streamletStatus.podStatuses)
        }
        .getOrElse(newStreamletStatus)
    }
    status.copy(
      appId = newApp.spec.appId,
      appVersion = newApp.spec.appVersion,
      streamletStatuses = newStreamletStatuses,
      appStatus = calcAppStatus(newStreamletStatuses))
  }

  private def updatePod(status: App.AppStatus)(streamletName: String, pod: Pod): App.AppStatus = {
    val streamletStatus =
      status.streamletStatuses
        .find(_.streamletName == streamletName)
        .map { streamletStatus => deletePod(streamletStatus)(pod) }
        .toList
    val streamletStatusesUpdated =
      status.streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
    status.copy(streamletStatuses = streamletStatusesUpdated, appStatus = calcAppStatus(streamletStatusesUpdated))
  }

  private def deletePod(status: App.AppStatus)(streamletName: String, pod: Pod): App.AppStatus = {
    val streamletStatus =
      status.streamletStatuses
        .find(_.streamletName == streamletName)
        .map { streamletStatus => deletePod(streamletStatus)(pod) }
        .toList
    val streamletStatusesUpdated =
      status.streamletStatuses.filterNot(_.streamletName == streamletName) ++ streamletStatus
    status.copy(streamletStatuses = streamletStatusesUpdated, appStatus = calcAppStatus(streamletStatusesUpdated))
  }

  private def ignoreOnErrorStatus(oldApp: Option[App.Cr], newApp: App.Cr) = {
    val _ = newApp
    oldApp.map(_.status) match {
      case Some(status) if status.appStatus == Some(Status.Error) => false
      case _                                                      => true
    }
  }

  // TODO 60 retries looks a pretty high number, got it since with 5 it happened to fail ...
  def statusUpdateAction(app: App.Cr)(retry: Int = 60): Action = {
    Action.operation[App.Cr, App.List, Try[App.Cr]](
      { client: KubernetesClient =>
        client
          .customResources(App.customResourceDefinitionContext, classOf[App.Cr], classOf[App.List])
      }, { cr: MixedOperation[App.Cr, App.List, Resource[App.Cr]] =>
        Try {
          val current = cr.inNamespace(app.namespace).withName(app.name)
          val res =
            Option(current.get()) match {
              case Some(curr) =>
                curr.copy(status = app.status)
              case _ =>
                app.copy(status = app.status)
            }
          current.updateStatus(res)
        }
      }, { res =>
        res match {
          case Success(_) => Action.noop
          case Failure(err) if retry > 0 =>
            log.error(s"Failure updating the CR status retries: $retry", err)
            statusUpdateAction(app)(retry - 1)
          case Failure(err) =>
            log.error("Failure updating the CR status retries exhausted, giving up", err)
            throw err
        }
      })
  }
}
