package de.experiments.clustering

import de.evaluation.f1.{Eval, F1, FullResult}
import de.evaluation.util.{DataSetCreator, SparkLOAN}
import de.experiments.ExperimentsCommonConfig
import org.apache.spark.ml.clustering.{BisectingKMeans, KMeans}
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}

/**
  * Created by visenger on 24/03/17.
  */
class Clustering {

}

object ClusteringAllRunner {
  def main(args: Array[String]): Unit = {
    TruthMatrixClusteringRunner.run()
    ErrorMatrixClusteringRunner.run()
  }
}

object TruthMatrixClusteringRunner extends ExperimentsCommonConfig {
  def main(args: Array[String]): Unit = {
    run()
  }

  def run(): Unit = {

    SparkLOAN.withSparkSession("WAHRHEITSMATRIX") {
      session => {

        println(s"CLUSTERING ON TRUTH MATRIX")
        val dataSetName = "ext.blackoak"
        import org.apache.spark.sql.functions._
        import session.implicits._

        val trainDF = DataSetCreator.createFrame(session, extBlackoakTrainFile, FullResult.schema: _*)

        val nxor = udf { (label: String, tool: String) => if (label.equals(tool)) "1" else "0" }
        val sum = udf { features: Vector => s"${features.numNonzeros} / ${features.size} " }
        val getRealName = udf { alias: String => getExtName(alias) }

        var truthDF = trainDF

        val tools = FullResult.tools
        tools.foreach(tool => {
          truthDF = truthDF
            .withColumn(s"truth-$tool", nxor(trainDF("label"), trainDF(tool)))
        })

        val truthTools: Seq[String] = tools.map(t => s"truth-$t")


        var labelAndTruth = truthDF.select(FullResult.label, truthTools: _*)
        tools.foreach(tool => {
          labelAndTruth = labelAndTruth.withColumnRenamed(s"truth-$tool", tool)
        })

        /*TRANSPOSE MATRIX*/

        val columns: Seq[(String, Column)] = tools.map(t => (t, labelAndTruth(t)))

        val transposedDF: DataFrame = columns.map(column => {
          val columnName = column._1
          val columnForTool = labelAndTruth.select(column._2)
          val toolsVals: Array[Double] = columnForTool
            .rdd
            .map(element => element.getString(0).toDouble)
            .collect()
          val valsVector: Vector = Vectors.dense(toolsVals)
          (columnName, valsVector)
        }).toDF("tool-name", "features")


        transposedDF.withColumn(s"correct/total", sum(transposedDF("features"))).show()


        val indexer = new StringIndexer()
          .setInputCol("tool-name")
          .setOutputCol("label")

        val truthMatrixWithIndx = indexer
          .fit(transposedDF)
          .transform(transposedDF)

        (2 to 4).foreach(k => {
          println(s"k = $k")
          val kMeans = new KMeans()
            //          .setMaxIter(200)
            .setK(k)
          //.setSeed(5L)


          val kMeansModel = kMeans.fit(truthMatrixWithIndx)
          val kMeansClusters: DataFrame = kMeansModel
            .transform(truthMatrixWithIndx)
            .withColumn("tool", truthMatrixWithIndx("tool-name"))
            .toDF()

          val kMeansResult = kMeansClusters
            .select("prediction", "tool")
            .groupByKey(row => {
              row.getInt(0)
            }).mapGroups((num, row) => {
            val clusterTools: Seq[String] = row.map(_.getString(1)).toSeq
            (num, clusterTools.mkString(splitter))
          }).rdd.collect().toSeq

          println(s"kMeans")
          kMeansResult.foreach(cluster => {
            val evals: String = evaluateCluster(session, dataSetName, trainDF, cluster)
            println(evals)
          })

          //hierarchical clustering
          val bisectingKMeans = new BisectingKMeans()
            //.setSeed(5L)
            .setK(k)
          val bisectingKMeansModel = bisectingKMeans.fit(truthMatrixWithIndx)
          val bisectingKMeansClusters = bisectingKMeansModel
            .transform(truthMatrixWithIndx)
            .withColumn("tool", truthMatrixWithIndx("tool-name"))


          val bisectingKMeansResult: Seq[(Int, String)] = bisectingKMeansClusters
            .select("prediction", "tool")
            .groupByKey(row => {
              row.getInt(0)
            }).mapGroups((num, row) => {
            val clusterTools: Seq[String] = row.map(_.getString(1)).toSeq
            (num, clusterTools.mkString(splitter))
          }).rdd.collect().toSeq

          println(s"bisecting kMeans")
          bisectingKMeansResult.foreach(cluster => {
            val evals: String = evaluateCluster(session, dataSetName, trainDF, cluster)
            println(evals)

          })
        })

      }
    }
  }


  private def evaluateCluster(session: SparkSession, dataSetName: String, trainDF: DataFrame, cluster: (Int, String)) = {

    val nr = cluster._1
    val tools: Seq[String] = cluster._2.split(splitter).toSeq

    val toolsToEval: DataFrame = trainDF.select(FullResult.label, tools: _*)

    val linearCombi = F1.evaluateLinearCombiWithLBFGS(session, dataSetName, tools)

    val bayesCombi = F1.evaluateLinearCombiWithNaiveBayes(session, dataSetName, tools)

    val unionAll: Eval = F1.evaluate(toolsToEval)

    val num = tools.size
    //  unionAll.printResult("Union All: ")
    val minK: Eval = F1.evaluate(toolsToEval, num)
    //  minK.printResult(s"min-$num")

    val toolsRealNames: Seq[String] = tools.map(getExtName(_))

    val latexBruteForceRow =
      s"""
         |\\multirow{4}{*}{\\begin{tabular}[c]{@{}l@{}}${toolsRealNames.mkString("+")}\\\\ $$ ${linearCombi.info} $$ \\end{tabular}}
         |                                                                        & UnionAll & ${unionAll.precision} & ${unionAll.recall} & ${unionAll.f1}  \\\\
         |                                                                        & Min-$num    & ${minK.precision} & ${minK.recall} & ${minK.f1}  \\\\
         |                                                                        & LinComb  & ${linearCombi.precision} & ${linearCombi.recall} & ${linearCombi.f1} \\\\
         |                                                                        & NaiveBayes  & ${bayesCombi.precision} & ${bayesCombi.recall} & ${bayesCombi.f1} \\\\
         |\\midrule
         |
                 """.stripMargin

    latexBruteForceRow
  }
}

object ErrorMatrixClusteringRunner extends ExperimentsCommonConfig {
  def main(args: Array[String]): Unit = {
    run()
  }

  def run(): Unit = {

    SparkLOAN.withSparkSession("ERRORMATRIX-CLUSTERING") {
      session => {
        val dataSetName = "ext.blackoak"
        println(s"CLUSTERING ON ERROR MATRIX")

        import org.apache.spark.sql.functions._
        import session.implicits._

        val trainDF = DataSetCreator.createFrame(session, extBlackoakTrainFile, FullResult.schema: _*)

        val sum = udf { features: Vector => s"${features.numNonzeros} / ${features.size} " }
        val getRealName = udf { alias: String => getExtName(alias) }

        val tools = FullResult.tools

        /*TRANSPOSE MATRIX*/
        // val columns: Seq[(String, Column)] = tools.map(t => (t, trainDF(t)))

        val transposedDF: DataFrame = tools.map(columnName => {
          //val columnName = tool
          val columnForTool = trainDF.select(columnName)
          val toolsVals: Array[Double] = columnForTool
            .rdd
            .map(element => element.getString(0).toDouble)
            .collect()
          val valsVector: Vector = Vectors.dense(toolsVals)
          (columnName, valsVector)
        }).toDF("tool-name", "features")

        val indexer = new StringIndexer()
          .setInputCol("tool-name")
          .setOutputCol("label")

        val truthMatrixWithIndx = indexer
          .fit(transposedDF)
          .transform(transposedDF)

        (2 to 4).foreach(k => {
          println(s"k = $k")
          val kMeans = new KMeans()
            //.setMaxIter(200)
            .setK(k)
          //.setSeed(5L)


          val kMeansModel = kMeans.fit(truthMatrixWithIndx)
          val kMeansClusters: DataFrame = kMeansModel
            .transform(truthMatrixWithIndx)
            .withColumn("tool", truthMatrixWithIndx("tool-name"))
            .toDF()

          val kMeansResult: Seq[(Int, String)] = kMeansClusters.select("prediction", "tool").groupByKey(row => {
            row.getInt(0)
          }).mapGroups((num, row) => {
            val clusterTools: Seq[String] = row.map(_.getString(1)).toSeq
            (num, clusterTools.mkString(splitter))
          }).rdd.collect().toSeq

          println(s"kMeans")

          kMeansResult.foreach(cluster => {
            val latexBruteForceRow: String = evaluateCluster(session, dataSetName, trainDF, cluster)
            println(latexBruteForceRow)
          })

          //hierarchical clustering
          val bisectingKMeans = new BisectingKMeans()
            //  .setSeed(5L)
            .setK(k)

          val bisectingKMeansModel = bisectingKMeans.fit(truthMatrixWithIndx)
          val bisectingKMeansClusters = bisectingKMeansModel
            .transform(truthMatrixWithIndx)
            .withColumn("tool", truthMatrixWithIndx("tool-name"))
            .toDF()


          val bisectingKMeansResult: Seq[(Int, String)] = bisectingKMeansClusters.select("prediction", "tool").groupByKey(row => {
            row.getInt(0)
          }).mapGroups((num, row) => {
            val clusterTools: Seq[String] = row.map(_.getString(1)).toSeq
            (num, clusterTools.mkString(splitter))
          }).rdd.collect().toSeq

          println(s"bisecting kMeans")
          bisectingKMeansResult.foreach(cluster => {
            val evals: String = evaluateCluster(session, dataSetName, trainDF, cluster)
            println(evals)
          })
        })

      }
    }
  }

  private def evaluateCluster(session: SparkSession, dataSetName: String, trainDF: DataFrame, cluster: (Int, String)) = {

    val nr = cluster._1
    val tools: Seq[String] = cluster._2.split(splitter).toSeq

    val toolsToEval: DataFrame = trainDF.select(FullResult.label, tools: _*)

    val linearCombi = F1.evaluateLinearCombiWithLBFGS(session, dataSetName, tools)

    val bayesCombi = F1.evaluateLinearCombiWithNaiveBayes(session, dataSetName, tools)

    val unionAll: Eval = F1.evaluate(toolsToEval)

    val num = tools.size
    //  unionAll.printResult("Union All: ")
    val minK: Eval = F1.evaluate(toolsToEval, num)
    //  minK.printResult(s"min-$num")

    val toolsRealNames: Seq[String] = tools.map(getExtName(_))

    val latexBruteForceRow =
      s"""
         |\\multirow{4}{*}{\\begin{tabular}[c]{@{}l@{}}${toolsRealNames.mkString("+")}\\\\ $$ ${linearCombi.info} $$ \\end{tabular}}
         |                                                                        & UnionAll & ${unionAll.precision} & ${unionAll.recall} & ${unionAll.f1}  \\\\
         |                                                                        & Min-$num    & ${minK.precision} & ${minK.recall} & ${minK.f1}  \\\\
         |                                                                        & LinComb  & ${linearCombi.precision} & ${linearCombi.recall} & ${linearCombi.f1} \\\\
         |                                                                        & NaiveBayes  & ${bayesCombi.precision} & ${bayesCombi.recall} & ${bayesCombi.f1} \\\\
         |\\midrule
         |
                 """.stripMargin

    latexBruteForceRow
  }
}

