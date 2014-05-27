# StackOverflowError with WS consumer Iteratee and large stream

The goal is to have a WS consume function that can handle a large multipart document.
The multipart parser is based on Play's [multipart BodyParser](https://github.com/playframework/playframework/blob/92078f9cc751a5c19117dede18c7ca63aca73347/framework/src/play/src/main/scala/play/api/mvc/ContentTypes.scala#L615)
and adapted to be used with WS.
To use the multipart parser with WS the `WS.url(String).get(WSResponseHeaders => Iteratee[Array[Byte], A])` interface is used.

The implemented multipart handler (see [app/multipart/WSMultipartHandler.scala](https://github.com/magro/play-iteratee-stackoverflow/blob/master/app/multipart/WSMultipartHandler.scala)) does work with
smallish multipart responses, but with larger multipart responses there's a `StackOverflowError`.

The [WSMultipartHandlerSpec](https://github.com/magro/play-iteratee-stackoverflow/blob/master/test/multipart/WSMultipartHandlerSpec.scala) shows this issue, the output of the failing test can be seen in [error.txt](https://github.com/magro/play-iteratee-stackoverflow/blob/master/error.txt).

To reproduce the issue just run `sbt test`.
