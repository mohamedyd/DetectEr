package de.generator.cleanpath


object StringGenerator extends App {
  val rules: String = (0 to 39).map(d => s"detect $d").mkString("\n")

  println(rules)
}

