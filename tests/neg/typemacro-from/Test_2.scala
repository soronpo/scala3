import structural.From

object Test:
  // `Int` is not a concrete case class: the macro aborts with a message.
  val x: From[Int] = ??? // error
