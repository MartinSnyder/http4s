package org.http4s.client.blaze

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import org.http4s._
import org.http4s.blaze.http.http_parser.Http1ClientParser
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.{Cancellable, Execution}

import scala.collection.mutable.ListBuffer
import scala.concurrent.TimeoutException
import scala.util.{Failure, Success}
import scalaz.concurrent.Task
import scalaz.stream.Cause.{End, Terminated}
import scalaz.stream.Process
import scalaz.{\/, -\/, \/-}

abstract class Http1ClientReceiver extends Http1ClientParser with BlazeClientStage { self: Http1ClientStage =>
  private val _headers = new ListBuffer[Header]
  private var _status: Status = _
  private var _httpVersion: HttpVersion = _
  @volatile private var closed = false

  protected val inProgress = new AtomicReference[TimeoutException\/Cancellable]()

  final override def isClosed(): Boolean = closed

  final override def shutdown(): Unit = stageShutdown()

  // seal this off, use shutdown()
  final override def stageShutdown() = {
    closed = true
    sendOutboundCommand(Command.Disconnect)
//    shutdownParser()
    super.stageShutdown()
  }

  final override def reset(): Unit = {
    inProgress.getAndSet(null) match {
      case \/-(c) => c.cancel()
      case _      => // NOOP
    }

    _headers.clear()
    _status = null
    _httpVersion = null

    super.reset()
  }

  final override protected def submitResponseLine(code: Int, reason: String,
                                                scheme: String,
                                          majorversion: Int,
                                          minorversion: Int): Unit = {
    _status = Status.fromIntAndReason(code, reason).valueOr(e => throw new ParseException(e))
    _httpVersion = {
      if (majorversion == 1 && minorversion == 1)  HttpVersion.`HTTP/1.1`
      else if (majorversion == 1 && minorversion == 0)  HttpVersion.`HTTP/1.0`
      else HttpVersion.fromVersion(majorversion, minorversion).getOrElse(HttpVersion.`HTTP/1.0`)
    }
  }

  final protected def collectMessage(body: EntityBody): Response = {
    val status   = if (_status == null) Status.InternalServerError else _status
    val headers  = if (_headers.isEmpty) Headers.empty else Headers(_headers.result())
    val httpVersion = if (_httpVersion == null) HttpVersion.`HTTP/1.0` else _httpVersion // TODO Questionable default

    Response(status, httpVersion, headers, body)
  }

  final override protected def headerComplete(name: String, value: String): Boolean = {
    _headers += Header(name, value)
    false
  }

  final protected def receiveResponse(cb: Callback, close: Boolean): Unit =
    readAndParse(cb, close, "Initial Read")

  // this method will get some data, and try to continue parsing using the implicit ec
  private def readAndParse(cb: Callback,  closeOnFinish: Boolean, phase: String): Unit = {
    channelRead().onComplete {
      case Success(buff) => requestLoop(buff, closeOnFinish, cb)
      case Failure(EOF)  => inProgress.get match {
        case e@ -\/(_) => cb(e)
        case _         => shutdown(); cb(-\/(EOF))
      }

      case Failure(t)    =>
        fatalError(t, s"Error during phase: $phase")
        cb(-\/(t))
    }
  }

  private def requestLoop(buffer: ByteBuffer, closeOnFinish: Boolean, cb: Callback): Unit = try {
    if (!responseLineComplete() && !parseResponseLine(buffer)) {
      readAndParse(cb, closeOnFinish, "Response Line Parsing")
      return
    }

    if (!headersComplete() && !parseHeaders(buffer)) {
      readAndParse(cb, closeOnFinish, "Header Parsing")
      return
    }

    def terminationCondition() = inProgress.get match {  // if we don't have a length, EOF signals the end of the body.
      case -\/(e) => e
      case _      =>
        if (definedContentLength() || isChunked()) InvalidBodyException("Received premature EOF.")
        else Terminated(End)
    }
    // We are to the point of parsing the body and then cleaning up
    val (rawBody, cleanup) = collectBodyFromParser(buffer, terminationCondition)

    val body = rawBody ++ Process.eval_(Task.async[Unit] { cb =>
      if (closeOnFinish) {
        logger.debug("Message body complete. Shutting down.")
        stageShutdown()
        cb(\/-(()))
      }
      else {
        logger.debug("Running client cleanup.")
        cleanup().onComplete { t =>
          logger.debug(s"Client body cleanup result: $t")
          t match {
            case Success(_)   =>
              reset()
              cb(\/-(()))     // we shouldn't have any leftover buffer

            case Failure(EOF) =>
              stageShutdown()
              cb(\/-(()))

            case Failure(t)   =>
              cb(-\/(t))
          }
        }(Execution.trampoline)
      }
    })

    // TODO: we need to detect if the other side has signaled the connection will close.
    cb(\/-(collectMessage(body)))
  } catch {
    case t: Throwable =>
      logger.error(t)("Error during client request decode loop")
      cb(-\/(t))
  }
}
