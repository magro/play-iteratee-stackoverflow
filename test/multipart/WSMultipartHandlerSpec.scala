package multipart

import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.test.FakeApplication
import play.api.mvc.Action
import play.api.mvc.Results.Ok
import java.io.{OutputStream, BufferedOutputStream, FileOutputStream, File}
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import play.api.libs.iteratee.{Enumerator, Done, Input, Cont, Iteratee}
import play.api.Logger
import play.api.libs.ws.WS
import multipart.WSMultipartHandler.consumeMultipart
import akka.util.Timeout
import play.api.test.Helpers.await
import org.scalatest.EitherValues
import com.ning.http.client.AsyncHttpClient

class WSMultipartHandlerSpec extends PlaySpec with OneServerPerSuite with EitherValues {

  private val header =
    """--frontier
      |Content-Disposition: inline
      |Content-Transfer-Encoding: 8bit
      |Content-Type: text/plain
      |Link: </things/foo>
    """.stripMargin.trim

  private val smallMP = multipartFile(2, 2)
  private val largeMP = multipartFile(4000, 5000)

  implicit override lazy val app: FakeApplication =
    FakeApplication(
      withRoutes = {
        case ("GET", "/small") => Action {
          import play.api.libs.iteratee.Execution.Implicits.defaultExecutionContext
          Ok.chunked(Enumerator.fromFile(smallMP, 1024)).as("multipart/package; boundary=\"frontier\"")
        }
        case ("GET", "/large") => Action {
          import play.api.libs.iteratee.Execution.Implicits.defaultExecutionContext
          Ok.chunked(Enumerator.fromFile(largeMP, 1024)).as("multipart/package; boundary=\"frontier\"")
        }
      }
    )

  private def multipartFile(partSize: Int, partCount: Int): File = {
    val part = new String((Array.fill(partSize)("a".getBytes).flatten))
    val multipart = (List.fill(partCount)(header + "\n\n" + part).mkString("\n") + "\n--frontier--").replaceAll("\n", "\r\n")
    val f = File.createTempFile(s"multipart-$partSize-$partCount", "tmp")
    Files.write(f.toPath, multipart.getBytes)
    f
  }

  "WS" should {
    "handle small multipart" in {
      doTest("/small", 2)
    }
    "handle large multipart" in {
      doTest("/large", 5000)
    }
  }

  private def doTest(path: String, expectedParts: Int) {
    val parts = new AtomicInteger(0)

    def partConsumer(headers: Map[String, String]): Iteratee[Array[Byte], Unit] = {
      headers.get("link").map { link =>
        var buffer = Array[Byte]()
        lazy val res: Iteratee[Array[Byte], Unit] = Cont {
          case e@Input.EOF =>
            parts.incrementAndGet()
            if (parts.intValue() % 1000 == 0) Logger.info(s"Imported ${parts.intValue()} parts until now")
            Done((), e)
          case in@Input.El(data) =>
            buffer ++= data
            res
          case Input.Empty => res
        }
        res
      }.getOrElse(Done((), Input.Empty))

    }

    import play.api.libs.iteratee.Execution.Implicits.trampoline
    val futureResponse = WS.url(s"http://localhost:$port$path")
      .get(consumeMultipart(partConsumer))
      .flatMap(_.run)

    import scala.concurrent.duration._
    implicit val defaultAwaitTimeout: Timeout = 600.seconds
    val response = await(futureResponse)

    response.right.value._1.status mustBe 200
    response.right.value._2 mustBe expectedParts

    parts.intValue() mustBe expectedParts
  }

  private def doTest3(path: String, expectedParts: Int) {
    val parts = new AtomicInteger(0)

    def partConsumer(headers: Map[String, String]): Iteratee[Array[Byte], Unit] = {
      Done((), Input.Empty)
    }

    import play.api.libs.iteratee.Execution.Implicits.trampoline
    val futureResponse = WS.url(s"http://localhost:$port$path")
      .get(consumeMultipart(partConsumer))
      .flatMap(_.run)

    import scala.concurrent.duration._
    implicit val defaultAwaitTimeout: Timeout = 600.seconds
    val response = await(futureResponse)

    response.right.value._1.status mustBe 200
    response.right.value._2 mustBe expectedParts

    parts.intValue() mustBe expectedParts
  }

  private def doTest2(path: String, expectedSize: Int) {

    val size = new AtomicInteger(0)

    def fromStream(stream: OutputStream): Iteratee[Array[Byte], Unit] = Cont {
      case e@Input.EOF =>
        stream.close()
        Done((), e)
      case Input.El(data) =>
        size.addAndGet(data.length)
        stream.write(data)
        fromStream(stream)
      case Input.Empty =>
        fromStream(stream)
    }

    val outputStream: OutputStream = new BufferedOutputStream(new FileOutputStream("/tmp" + path))

    import play.api.libs.iteratee.Execution.Implicits.trampoline
    val futureResponse = WS.url(s"http://localhost:$port$path")
      .get(headers => fromStream(outputStream))
      .flatMap(_.run)

    import scala.concurrent.duration._
    implicit val defaultAwaitTimeout: Timeout = 600.seconds
    val response = await(futureResponse)

    size.intValue() mustBe expectedSize
  }

}
