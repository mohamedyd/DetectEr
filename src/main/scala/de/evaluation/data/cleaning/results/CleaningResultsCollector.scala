package de.evaluation.data.cleaning.results

import de.evaluation.data.util.SchemaUtil
import de.evaluation.f1.FullResult
import de.evaluation.tools.pattern.violation.TrifactaResults
import de.evaluation.tools.ruleviolations.nadeef.NadeefRulesVioResults
import de.evaluation.util.SparkLOAN
import de.experiments.features.error.prediction.ErrorsPredictor
import org.apache.spark.sql.DataFrame

object CleaningResultsCollector {

  def main(args: Array[String]): Unit = {

    SparkLOAN.withSparkSession("CLEAN-MODEL") {
      session => {

        // val datasets = Seq(/*"blackoak", "hosp", "salaries",*/ "flights")
        val dataset = "flights"

        val rulesVioResults = new NadeefRulesVioResults()
        rulesVioResults.onData(dataset)
        val tmp_rulesVioResultDF: DataFrame = rulesVioResults.createRepairLog(session)
        val repairColFromRulesVio = "newvalue-1"
        val rulesVioResultDF = tmp_rulesVioResultDF.withColumnRenamed("newvalue", repairColFromRulesVio)
        rulesVioResultDF.printSchema()

        val patternVioResults = new TrifactaResults()
        patternVioResults.onDataset(dataset)
        val tmp_patternVioResultDF: DataFrame = patternVioResults.createRepairLog(session)
        val repairColFromPattenVio = "newvalue-2"
        val patternVioResultDF = tmp_patternVioResultDF.withColumnRenamed("newvalue", repairColFromPattenVio)
        patternVioResultDF.printSchema()

        val allResults = Seq(rulesVioResultDF, patternVioResultDF)
        val fullRepairResultsDF: DataFrame = allResults.tail
          .foldLeft(allResults.head)((acc, tool) => acc.join(tool, SchemaUtil.joinCols, "full_outer"))
        val fullRepairResultsFilledDF = fullRepairResultsDF
          .na
          .fill("#NO-REPAIR#", Seq(repairColFromRulesVio, repairColFromPattenVio))

        fullRepairResultsFilledDF
          //.where(fullRepairResultsDF(repairColFromPattenVio) =!= "#NO-REPAIR#")
          .show(200, false)

        val predictedErrorsDF: DataFrame = ErrorsPredictor()
          .onDataset(dataset)
          .runPredictionWithStacking(session)

        predictedErrorsDF.printSchema()

        //todo: we will need value field to create a sub-sets of values: dirty and clean.

        predictedErrorsDF
          .select(FullResult.recid, FullResult.attrnr, "final-predictor")
          .join(fullRepairResultsFilledDF, SchemaUtil.joinCols)
          .show(45, false)




        //todo: create results from nadeef
        //todo: create results from trifacta
        //todo: nadeef deduplication. extend every class with method: public Collection<Fix> repair(Violation violation) {
        //todo: run nadeef deduplication repair.

        //todo: combine all of them into one
      }
    }
  }

}
