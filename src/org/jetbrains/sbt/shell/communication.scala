package org.jetbrains.sbt.shell

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean

import com.intellij.execution.process.{AnsiEscapeDecoder, OSProcessHandler, ProcessAdapter, ProcessEvent}
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.task.ProjectTaskResult
import org.jetbrains.ide.PooledThreadExecutor
import org.jetbrains.plugins.scala.lang.psi.api.base.A
import org.jetbrains.sbt.shell.SbtProcessUtil._
import org.jetbrains.sbt.shell.SbtShellCommunication._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationLong}
import scala.concurrent.{Future, Promise}
import scala.util.{Success, Try}

/**
  * Created by jast on 2016-11-06.
  */
class SbtShellCommunication(project: Project) extends AbstractProjectComponent(project) {

  private lazy val process = SbtProcessManager.forProject(project)

  private val shellPromptReady = new AtomicBoolean(false)
  private val communicationActive = new AtomicBoolean(false)
  private val shellQueueReady = new Semaphore(1)
  private val commands = new LinkedBlockingQueue[(String, CommandListener[_])]()

  /** Queue an sbt command for execution in the sbt shell, returning a Future[String] containing the entire shell output. */
  def command(cmd: String, showShell: Boolean = true): Future[String] =
    command(cmd, StringBuilder.newBuilder, messageAggregator, showShell).map(_.toString())

  /** Queue an sbt command for execution in the sbt shell. */
  def command[A](cmd: String,
                 default: A,
                 eventHandler: EventAggregator[A],
                 showShell: Boolean): Future[A] = {
    val listener = new CommandListener(default, eventHandler)
    if (showShell) process.openShellRunner()
    initCommunication(process.acquireShellProcessHandler)
    commands.put((cmd, listener))
    listener.future
  }


  /** Start processing command queue if it is not yet active. */
  private def startQueueProcessing(handler: OSProcessHandler): Unit = {

    // is it ok for this executor to run a queue processor?
    PooledThreadExecutor.INSTANCE.submit(new Runnable {
      override def run(): Unit = {
        // make sure there is exactly one permit available
        shellQueueReady.drainPermits()
        shellQueueReady.release()
        while (!handler.isProcessTerminating && !handler.isProcessTerminated) {
          nextQueuedCommand(1.second)
        }
        communicationActive.set(false)
      }
    })
  }

  private def nextQueuedCommand(timeout: Duration) = {
    // TODO exception handling
    if (shellQueueReady.tryAcquire(timeout.toMillis, TimeUnit.MILLISECONDS)) {
      val next = commands.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
      if (next != null) {
        val (cmd, listener) = next

        process.attachListener(listener)

        process.usingWriter { shell =>
          shell.println(cmd)
          shell.flush()
        }

        // we want to avoid registering multiple callbacks to the same output and having whatever side effects
        listener.future.onComplete { _ =>
          process.removeListener(listener)
          shellQueueReady.release()
        }
      } else shellQueueReady.release()
    }
  }

  /**
    * To be called when the process is reinitialized externally.
    * Will only work correctly when `acquireShellProcessHandler.isStartNotify == true`
    * This is usually ensured by calling openShellRunner first, but it's possible
    * to manually trigger it if a fully background process is desired
    */
  private[shell] def initCommunication(handler: OSProcessHandler): Unit = {
    if (!communicationActive.getAndSet(true)) {
      val stateChanger = new SbtShellReadyListener(
        whenReady = shellPromptReady.set(true),
        whenWorking = shellPromptReady.set(false)
      )

      shellPromptReady.set(false)
      process.attachListener(stateChanger)

      startQueueProcessing(handler)
    }
  }
}

object SbtShellCommunication {
  def forProject(project: Project): SbtShellCommunication = project.getComponent(classOf[SbtShellCommunication])

  sealed trait ShellEvent
  case object TaskStart extends ShellEvent
  case object TaskComplete extends ShellEvent
  case class Output(line: String) extends ShellEvent

  type EventAggregator[A] = (A, ShellEvent) => A

  /** Aggregates the output of a shell task. */
  val messageAggregator: EventAggregator[StringBuilder] = (builder, e) => e match {
    case TaskStart | TaskComplete => builder
    case Output(text) => builder.append("\n", text)
  }

  /** Convenience aggregator wrapper that is executed for the side effects.
    * The final result will just be the value of the last invocation. */
  def listenerAggregator[A](listener: ShellEvent => A): EventAggregator[A] = (_,e) =>
    listener(e)
}

private[shell] class CommandListener[A](default: A, aggregator: EventAggregator[A]) extends LineListener {

  private val promise = Promise[A]()
  private var a: A = default

  private def aggregate(event: ShellEvent) = {
    a = aggregator(a, event)
  }

  def future: Future[A] = promise.future

  override def startNotified(event: ProcessEvent): Unit = aggregate(TaskStart)

  override def processTerminated(event: ProcessEvent): Unit = {
    // TODO separate event type for completion by termination?
    aggregate(TaskComplete)
    promise.complete(Try(a))
  }

  override def onLine(text: String): Unit = {

    if (!promise.isCompleted && promptReady(text)) {
      aggregate(TaskComplete)
      promise.complete(Success(a))
    } else {
      aggregate(Output(text))
    }
  }
}


/**
  * Monitor sbt prompt status, do something when state changes.
  *
  * @param whenReady callback when going into Ready state
  * @param whenWorking callback when going into Working state
  */
class SbtShellReadyListener(whenReady: =>Unit, whenWorking: =>Unit) extends LineListener {

  private var readyState: Boolean = false

  def onLine(line: String): Unit = {
    val sbtReady = promptReady(line)
    if (sbtReady && !readyState) {
      readyState = true
      whenReady
    }
    else if (!sbtReady && readyState) {
      readyState = false
      whenWorking
    }
  }
}

private[shell] object SbtProcessUtil {

  def promptReady(line: String): Boolean =
    line.trim match {
      case
        ">" | // todo can we guard against false positives? like somebody outputting > on the bare prompt
        "scala>" |
        "Hit enter to retry or 'exit' to quit:"
        => true

      case _ => false
    }
}

/**
  * Pieces lines back together from parts of colored lines.
  */
private[shell] abstract class LineListener extends ProcessAdapter with AnsiEscapeDecoder.ColoredTextAcceptor {

  private val builder = new StringBuilder

  def onLine(line: String): Unit

  override def onTextAvailable(event: ProcessEvent, outputType: Key[_]): Unit =
    updateLine(event.getText)

  override def coloredTextAvailable(text: String, attributes: Key[_]): Unit =
    updateLine(text)

  private def updateLine(text: String) = {
    text match {
      case "\n" =>
        lineDone()
      case t if t.endsWith("\n") =>
        builder.append(t.dropRight(1))
        lineDone()
      case t =>
        builder.append(t)
        val lineSoFar = builder.result()
        if (promptReady(lineSoFar)) lineDone()
    }
  }

  private def lineDone() = {
    val line = builder.result()
    builder.clear()
    onLine(line)
  }
}