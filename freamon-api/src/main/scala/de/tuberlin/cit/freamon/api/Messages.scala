package de.tuberlin.cit.freamon.api

import java.lang.Double

case class FindPreviousRuns(signature: String)

case class PreviousRuns(scaleOuts: Array[Integer], runtimes: Array[Double])

case class ApplicationStart(applicationId: String, startTime: Long)

case class ApplicationStop(applicationId: String, stopTime: Long)

case class StartMonitoringForApplication(applicationId: String, containerIds: Array[Long])

case class StopMonitoringForApplication(applicationId: String)

case class StartRecording(applicationId: String, containerIds: Array[String])

case class StopRecording(applicationId: String)

case class WorkerAnnouncement(workerHostname: String)

case class ContainerReport(applicationId: String, container: ContainerStats)

case class ApplicationMetadata(
                                appId: String,
                                framework: Symbol = Symbol(null),
                                signature: String = null,
                                datasetSize: Double = 0d,
                                coresPerContainer: Int = 0,
                                memoryPerContainer: Int = 0
                              )
