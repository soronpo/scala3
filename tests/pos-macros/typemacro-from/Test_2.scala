import structural.From
import scala.NamedTuple.NamedTuple

case class Person(name: String, age: Int)
case class Book(title: String, author: Person, year: Int)

object Test:
  // `From[Person]` reduces to the named tuple of its fields.
  val _ = summon[From[Person] =:= NamedTuple[("name", "age"), (String, Int)]]
  val _ = summon[From[Book] =:= NamedTuple[("title", "author", "year"), (String, Person, Int)]]

  val p: From[Person] = (name = "Bob", age = 33)
  val n: String = p.name
  val a: Int = p.age
