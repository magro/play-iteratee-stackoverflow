# Sample to demo a StackOverflowError with Play Framework Iteratees

The goal is to have a WS consume function that can handle a large multipart document.
For this the `WS.url(String).get(WSResponseHeaders => Iteratee[Array[Byte], A])` interface is used.

The implemented multipart handler (see `app/multipart/WSMultipartHandler.scala`) does work with
smallish multipart responses, but with larger multipart responses there's a `StackOverflowError`.

The `WSMultipartHandlerSpec` shows this issue, the output of the failing test can be seen in `error.txt`.

To reproduce the issue:

```
$ sbt
[play-iteratee-stackoverflow] $ test
```