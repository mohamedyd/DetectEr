package de.evaluation.util

import org.apache.spark.sql.SparkSession

/**
  * Created by visenger on 23/12/16.
  */
object SparkLOAN {

  def withSparkSession(name: String)(f: SparkSession => Unit): Unit = {
    val r = SparkSessionCreator.createSession(name)
    try {
      f(r)
    } finally {
      r.stop()
    }

  }

}
