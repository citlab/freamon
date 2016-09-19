package de.tuberlin.cit.freamon.monitor.actors

import java.lang.Double
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue

import akka.actor.{Actor, ActorSelection, Address}
import akka.event.Logging
import de.tuberlin.cit.freamon.api._
import de.tuberlin.cit.freamon.results._
import de.tuberlin.cit.freamon.yarnclient.yarnClient
import org.apache.hadoop.yarn.api.records.{ApplicationId, FinalApplicationStatus}
import de.tuberlin.cit.freamon.collector.AuditLogProducer

import scala.collection.mutable

case class StartProcessingAuditLog(path: String)

class MonitorMasterActor extends Actor {

  val log = Logging(context.system, this)
  var workers = mutable.Set[String]()
  val hostConfig = context.system.settings.config
  val yClient = new yarnClient
  var processAudit: Boolean = false
  var queue: LinkedBlockingQueue[AuditLogEntry] = new LinkedBlockingQueue[AuditLogEntry]()

  // setup DB connection
  implicit val conn = DB.getConnection(
    hostConfig.getString("freamon.monetdb.url"),
    hostConfig.getString("freamon.monetdb.user"),
    hostConfig.getString("freamon.monetdb.password"))
  DB.createSchema()

  override def preStart(): Unit = {
    log.info("Monitor Master started")
  }

  def makeYarnAppIdInstance(applicationId: String): ApplicationId = {
    val splitAppId = applicationId.split("_")
    val clusterTimestamp = splitAppId(1).toLong
    val id = splitAppId(2).toInt
    val yarnAppId = ApplicationId.newInstance(clusterTimestamp, id)
    yarnAppId
  }

  def getAgentActorOnHost(hostname: String): ActorSelection = {
    val agentSystem = new Address("akka.tcp", hostConfig.getString("freamon.actors.systems.slave.name"),
      hostname, hostConfig.getInt("freamon.hosts.slaves.port"))
    val agentActorPath = agentSystem.toString + "/user/" + hostConfig.getString("freamon.actors.systems.slave.actor")
    context.system.actorSelection(agentActorPath)
  }

    def startApplication(applicationId: String, startTime: Long) = {
      val containerIds = yClient
          .getApplicationContainerIds(makeYarnAppIdInstance(applicationId))
        .map(containerNr => {
          val attemptNr = 1 // TODO get from yarn, yarnClient assumes this to be 1
          val strippedAppId = applicationId.substring("application_".length)
          "container_%s_%02d_%06d".format(strippedAppId, attemptNr, containerNr)
        })

      for (host <- workers) {
        val agentActor = this.getAgentActorOnHost(host)
        agentActor ! StartRecording(applicationId, containerIds)
      }

      log.info(s"Job started: $applicationId at ${Instant.ofEpochMilli(startTime)}")

      val job = new JobModel(applicationId, start = startTime)
      try {
        JobModel.insert(job)
      } catch {
        case e: SQLException => log.error(s"Could not insert job $applicationId: ${e.getMessage}")
      }
    }

  def stopApplication(applicationId: String, stopTime: Long) = {
      // TODO do not stop if already stopped

      for (host <- workers) {
        val agentActor = this.getAgentActorOnHost(host)
        agentActor ! StopRecording(applicationId)
      }
      processAudit = false

      val status = yClient.yarnClient.getApplicationReport(makeYarnAppIdInstance(applicationId)).getFinalApplicationStatus

      JobModel.selectWhere(s"app_id = '$applicationId'").headOption.map(oldJob => {
        val sec = (stopTime - oldJob.start) / 1000f
        log.info(s"Job finished: $applicationId at ${Instant.ofEpochMilli(stopTime)}, took $sec seconds")
        JobModel.update(oldJob.copy(stop = stopTime))
        Unit
      }).orElse({
        log.error("No such job in DB: " + applicationId + " (ApplicationStop)")
        None
      })
    }

  def receive = {

    case ApplicationStart(applicationId, stopTime) =>
      this.startApplication(applicationId, stopTime)
    case Array("jobStarted", applicationId: String, stopTime: Long) =>
      this.startApplication(applicationId, stopTime)

    case ApplicationStop(applicationId, stopTime) =>
      this.stopApplication(applicationId, stopTime)
    case Array("jobStopped", applicationId: String, stopTime: Long) =>
      this.stopApplication(applicationId, stopTime)

    case ApplicationMetadata(appId, framework, signature, datasetSize, coresPC, memPC) => {
      JobModel.selectWhere(s"app_id = '$appId'").headOption.map(oldJob => {
        JobModel.update(oldJob.copy(appId,
          framework = framework,
          signature = signature,
          datasetSize = datasetSize,
          coresPerContainer = coresPC,
          memoryPerContainer = memPC))
        Unit
      }).getOrElse(log.warning("Cannot update application metadata for " + appId))
    }

    case FindPreviousRuns(signature) => {
      val runs = JobModel.selectWhere(s"signature = '$signature'")
      sender ! PreviousRuns(
        runs.map(_.numContainers.asInstanceOf[Integer]).toArray,
        runs.map(r => ((r.stop - r.start) / 1000d).asInstanceOf[Double]).toArray,
        runs.map(_.datasetSize.asInstanceOf[Double]).toArray
      )
    }

    case WorkerAnnouncement(workerHostname) => {
      log.info(sender + " registered a new worker on " + workerHostname)
      workers += workerHostname
    }

    case ContainerReport(applicationId, container) => {
      log.info("Received a container report of " + applicationId + " from " + sender)
      log.info("for container " + container.containerId + " with " + container.cpuUtil.length + " samples")
      log.debug("BlkIO: " + container.blkioUtil.mkString(", "))
      log.debug("CPU: " + container.cpuUtil.mkString(", "))
      log.debug("Net: " + container.netUtil.mkString(", "))
      log.debug("Memory: " + container.memUtil.mkString(", "))

      JobModel.selectWhere(s"app_id = '$applicationId'").headOption.map(job => {
        val hostname = sender().path.address.hostPort
        val containerModel = ContainerModel(container.containerId, job.id, hostname)
        ContainerModel.insert(containerModel)

        val containerStart = job.start + 1000 * container.startTick
        for ((io, i) <- container.blkioUtil.zipWithIndex) {
          EventModel.insert(new EventModel(containerModel.id, job.id, 'blkio, containerStart + 1000 * i, io))
        }
        for ((cpu, i) <- container.cpuUtil.zipWithIndex) {
          EventModel.insert(new EventModel(containerModel.id, job.id, 'cpu, containerStart + 1000 * i, cpu))
        }
        for ((net, i) <- container.netUtil.zipWithIndex) {
          EventModel.insert(new EventModel(containerModel.id, job.id, 'net, containerStart + 1000 * i, net))
        }
        for ((mem, i) <- container.memUtil.zipWithIndex) {
          EventModel.insert(new EventModel(containerModel.id, job.id, 'mem, containerStart + 1000 * i, mem))
        }
        Unit
      }).orElse({
        log.error("No such job in DB: " + applicationId + " (ContainerReport)")
        None
      })
    }

    case StartProcessingAuditLog(path) => {
      log.info("Starting to process the audit log...")
      val producer: AuditLogProducer = new AuditLogProducer(queue, path)
      new Thread(producer).start()
      try {
        while (true) {
          if (!queue.isEmpty) {
            println("Queue is not empty. Trying to take an entry...")
            val ale = queue.take()
            println("Received an entry with date: " + ale.date)
            log.debug("Contents: allowed=" + ale.allowed + ", ugi=" + ale.ugi + ", ip=" + ale.ip +
              ", cmd=" + ale.cmd + ", src=" + ale.src + ", dst=" + ale.dst + ", perm=" + ale.perm + ", proto=" + ale.proto)
            AuditLogModel.insert(new AuditLogModel(ale.date, ale.allowed,
              ale.ugi, ale.ip, ale.cmd, ale.src, ale.dst,
              ale.perm, ale.proto))
            println("Succeeded!")
          }
          else if (queue.isEmpty) {
            log.info("Currently no entries. Sleeping for a second")
            Thread.sleep(1000)
            log.info("Waked up. Trying again...")
          }

        }
      }

      catch {
        case ex: InterruptedException => {
          log.debug("Caught an InterruptedException: " + ex)
          ex.printStackTrace()
        }
      }
    }
  }

}