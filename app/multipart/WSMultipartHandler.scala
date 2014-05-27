package multipart

import play.api.libs.ws.WSResponseHeaders
import play.api.libs.iteratee._
import play.api.libs.iteratee.Parsing.MatchInfo
import play.api.http.{MediaType, HeaderNames}

/**
 * Created by magro on 5/7/14.
 */
object WSMultipartHandler {

  /**
   * An iteratee that consumes a multipart response (incoming stream of byte arrays) and invokes the provided
   * partHandler with each extracted part. As result, the iteratee returns either just the response headers (if
   * the response was not processed, e.g. due to a wrong status code), or the response headers and the number
   * of parts processed.
   *
   * The implementation is based on Play's BodyParser multipartParser
   * (https://github.com/playframework/playframework/blob/92078f9cc751a5c19117dede18c7ca63aca73347/framework/src/play/src/main/scala/play/api/mvc/ContentTypes.scala#L615).
   *
   * @param partHandler is invoked with each part plus one more time at the end (thus the handler must check if
   *                    the passed bytes are not empty) - this is already the case in the original implementation
   *                    of Play's BodyParser multipartParser (perhaps might be improved, perhaps there's a reason for this)
   * @param headers the headers used to create the iteratee. Must contain the "Content-Type" header to read the "boundary"
   *                parameter from.
   */
  def consumeMultipart(partHandler: Map[String, String] => Iteratee[Array[Byte], Unit])(headers: WSResponseHeaders):
    Iteratee[Array[Byte], Either[WSResponseHeaders, (WSResponseHeaders, Int)]] = headers.status match {

    case 200 => {
      val maybeBoundary = for {
        mt <- headers.headers.get(HeaderNames.CONTENT_TYPE).map(_.head).flatMap(MediaType.parse.apply)
        (_, value) <- mt.parameters.find(_._1.equalsIgnoreCase("boundary"))
        boundary <- value
      } yield ("\r\n--" + boundary).getBytes("utf-8")

      maybeBoundary.map { boundary =>
        multipartConsumer(headers, boundary, partHandler)
      }.getOrElse(Done(Left(headers)))
    }
    case _ => Done(Left(headers))

  }

  private def multipartConsumer(headers: WSResponseHeaders,
                               boundary: Array[Byte],
                               partHandler: (Map[String, String]) => Iteratee[Array[Byte], Unit]):
      Iteratee[Array[Byte], Either[WSResponseHeaders, (WSResponseHeaders, Int)]] = {

    // Use the trampoline EC to prevent StackOverflowErrors.
    import play.api.libs.iteratee.Execution.Implicits.trampoline

    val CRLF = "\r\n".getBytes
    val CRLFCRLF = CRLF ++ CRLF

    val takeUpToBoundary = Enumeratee.takeWhile[MatchInfo[Array[Byte]]](!_.isMatch)

    val maxHeaderBuffer = Traversable.takeUpTo[Array[Byte]](4 * 1024) transform Iteratee.consume[Array[Byte]]()

    val collectHeaders = maxHeaderBuffer.map { buffer =>
      val (headerBytes, rest) = Option(buffer.drop(2)).map(b => b.splitAt(b.indexOfSlice(CRLFCRLF))).get

      val headerString = new String(headerBytes, "utf-8")
      val headers = headerString.lines.map { header =>
        val key :: value = header.trim.split(":").toList
        (key.trim.toLowerCase, value.mkString.trim)
      }.toMap

      val left = rest.drop(CRLFCRLF.length)
      (headers, left)
    }

    val readPart = collectHeaders.flatMap {
      case (headers, left) => Iteratee.flatten(partHandler(headers).feed(Input.El(left)))
    }

    val handlePart = Enumeratee.map[MatchInfo[Array[Byte]]](_.content).transform(readPart)

    Traversable.take[Array[Byte]](boundary.size - 2).transform(Iteratee.consume()).flatMap { firstBoundary =>

      Parsing.search(boundary) transform Iteratee.repeat {

        takeUpToBoundary.transform(handlePart).flatMap { part =>
          Enumeratee.take(1)(Iteratee.ignore[MatchInfo[Array[Byte]]]).map(_ => part)
        }

      }.map(parts => Right(headers -> (parts.size - 1)))

    }
  }
}
