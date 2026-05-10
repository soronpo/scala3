// https://github.com/scala/scala3/issues/8646
// Regression check: when reporting a type mismatch where the expected type
// involves a match type, the match type should be reduced/normalized in the
// rendered output. Previously the unreduced `HandlerSingle[CREATED[...]]`
// surfaced in the error; now it shows the reduced `IO[SomeResponse]`.

object main:
  case class GET()
  case class CREATED[JSON, R]()
  case class :>[+A, +B]()
  final case class PathParam[A]()
  final case class RequestBody[A]()
  class IO[A](unsafePerformIO: () => A)
  object IO:
    def apply[A](a: => A) = new IO(() => a)
  class JSON
  case class SomeRequestBody()
  case class SomeResponse()

  type Handler[API] = API match
    case prev :> last => HandlerAux[prev, HandlerSingle[last]]
    case _ => HandlerSingle[API]

  type HandlerSingle[X] = X match
    case CREATED[_, response] => IO[response]
    case PathParam[param] => param
    case RequestBody[body] => body

  type HandlerAux[API, Next] = API match
    case prev :> last =>
      last match
        case PathParam[param] => HandlerAux[prev, param => Next]
        case RequestBody[body] => HandlerAux[prev, body => Next]
    case GET => Next
    case PathParam[param] => param => Next
    case RequestBody[body] => body => Next

  type CreateTransaction =
    GET :> PathParam[java.util.UUID] :> RequestBody[SomeRequestBody] :> CREATED[JSON, SomeResponse]

  def createTransaction: Handler[CreateTransaction] =
    (uuid: Int) => body => IO(SomeResponse()) // error
