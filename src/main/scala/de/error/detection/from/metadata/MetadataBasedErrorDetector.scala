package de.error.detection.from.metadata

import com.typesafe.config.{Config, ConfigFactory}
import de.evaluation.data.metadata.MetadataCreator
import de.evaluation.data.schema.{FlightsSchema, Schema}
import de.evaluation.f1.{Eval, F1}
import de.evaluation.util.SparkLOAN
import de.experiments.ExperimentsCommonConfig
import de.model.util.FormatUtil
import de.util.DatasetFlattener
import de.util.ErrorNotation._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame}

import scala.collection.convert.decorateAsScala._
import scala.collection.mutable

object UDF {

  val identify_missing = udf {
    isNull: Boolean =>
      isNull match {
        case true => ERROR
        case false => CLEAN
      }
  }

  def majority_vote = udf {
    classifiers: mutable.WrappedArray[Int] => {
      val totalSum: Int = classifiers.sum
      val result: Int = if (totalSum >= 0) ERROR else CLEAN
      result
    }
  }

  def min_1 = udf {
    classifiers: mutable.WrappedArray[Int] => {
      val result: Int = if (classifiers.contains(ERROR)) ERROR else CLEAN
      result
    }
  }

}


trait ConfigBase {

  val applicationConfig: Config = ConfigFactory.load()

  val commonsConfig: Config = ConfigFactory.load("data-commons.conf")
  val VALID_NUMBER = commonsConfig.getString("valid.number")
  val VALID_SSN = commonsConfig.getString("valid.ssn")

  val strDefaultValues: Seq[String] = commonsConfig.getStringList("default.string").asScala
  val intDefaultValues: Seq[String] = commonsConfig.getStringList("default.integer").asScala
  val dateDefaultValues: Seq[String] = commonsConfig.getStringList("default.date").asScala


}


/**
  * prototype to an error detection based on metadata information;
  */
object MetadataBasedErrorDetector extends ExperimentsCommonConfig with ConfigBase {

  def getDefaultsByDataType(dataType: String): Seq[String] = {
    dataType match {
      case "String" => strDefaultValues
      case "Integer" => intDefaultValues
      case "Date/Time" => dateDefaultValues
      //todo: extend with more defaults
      case _ => intDefaultValues
    }
  }

  def getContainsInDefaults(defaultValues: Seq[String], value: String): Int = {
    val containsInDefaults: Boolean = defaultValues.map(_.toLowerCase()).contains(value.toLowerCase())

    containsInDefaults match {
      case true => ERROR
      case false => CLEAN
    }
  }

  def main(args: Array[String]): Unit = {
    SparkLOAN.withSparkSession("metadata reader") {
      session => {

        Seq("beers", "flights", "blackoak").foreach(dataset => {
          println(s"processing $dataset.....")

          val metadataPath: String = allMetadataByName.getOrElse(dataset, "unknown")
          val creator = MetadataCreator()

          val dirtyDF: DataFrame = DatasetFlattener().onDataset(dataset).flattenDirtyData(session)
          val fullMetadataDF: DataFrame = creator.getMetadataWithCounts(session, metadataPath, dirtyDF)
          fullMetadataDF.show()

          val flatWithLabelDF: DataFrame = DatasetFlattener().onDataset(dataset).makeFlattenedDiff(session)

          val flatWithMetadataDF: DataFrame = flatWithLabelDF.join(fullMetadataDF, Seq("attrName"))

          //Error Classifier #1: missing values -> object UDF


          //Error Classifier #2: default values
          val schema: Schema = allSchemasByName.getOrElse(dataset, FlightsSchema)
          val dataTypesDictionary: Map[String, String] = schema.dataTypesDictionary

          def identify_default_values = udf {
            (attrName: String, value: String) => {

              var result: Int = DOES_NOT_APPLY

              if (value != null) {
                val dataType: String = dataTypesDictionary.getOrElse(attrName, "")

                dataType match {
                  case "String" => result = getContainsInDefaults(strDefaultValues, value)
                  case "Integer" => result = getContainsInDefaults(intDefaultValues, value)
                  case "Date/Time" => result = getContainsInDefaults(dateDefaultValues, value)
                  case _ => result = DOES_NOT_APPLY
                }
              }
              result
            }
          }

          //Error classifier #3: top-values (aka histogram)
          def is_top_value = udf {
            (value: String, attrName: String, top10Values: mutable.Seq[String]) => {

              var result: Int = DOES_NOT_APPLY

              if (value != null) {
                // remove default values from the top10
                val dataType: String = dataTypesDictionary.getOrElse(attrName, "")
                val defaults: Seq[String] = getDefaultsByDataType(dataType).map(_.toLowerCase())
                val topValsWithoutDefaults: mutable.Seq[String] = top10Values.map(_.toLowerCase()).diff(defaults)

                val valueInTop10: Boolean = topValsWithoutDefaults.contains(value.toLowerCase())
                valueInTop10 match {
                  case true => result = CLEAN
                  case false => result = ERROR
                }
              }
              result
            }
          }

          //Error Classifier #4: Semantic role for numbers
          def isValidNumber(value: String): Int = {

            val allDigits: Boolean = value.matches(VALID_NUMBER)
            allDigits match {
              case true => CLEAN
              case false => ERROR
            }
          }

          def is_valid_number = udf {
            (attrName: String, value: String) => {
              var result: Int = DOES_NOT_APPLY

              if (value != null) {
                //todo: remove default values from the top10
                val dataType: String = dataTypesDictionary.getOrElse(attrName, "")

                dataType match {
                  case "Integer" => result = isValidNumber(value)
                  case _ => result = DOES_NOT_APPLY
                }
              }
              result
            }
          }

          //Error Classifier #5: cardinality estimation for PK columns
          val allTuplesViolatingCardinality: List[String] = CardinalityEstimator()
            .dataSetName(dataset)
            .onSchema(schema)
            .forDirtyDataDF(dirtyDF)
            .forFullMetadataDF(fullMetadataDF)
            .getTuplesViolatedCardinality()

          println(s"the number of tuples violating cardinality constraint: ${allTuplesViolatingCardinality.size}")

          //Error Classifier #6: The column, related to the lookup source, its value is absent in the source -> error, else clean.
          //for unrelated columns -> 0

          val lookupsLoader: LookupsLoader = LookupsLoader()
            .dataSetName(dataset)
            .onSchema(schema)
            .forDirtyDataDF(dirtyDF)

          val lookupCols: Seq[String] = lookupsLoader.getLookupColumns()
          val lookupsByAttr: Map[String, Seq[String]] = lookupsLoader
            .load(session)

          def is_valid_by_lookup = udf {
            (attrName: String, recId: String) => {
              if (lookupCols.isEmpty || !lookupCols.contains(attrName)) DOES_NOT_APPLY
              else {
                if (lookupsByAttr(attrName).contains(recId)) ERROR
                else CLEAN

              }
            }
          }
          //end: Lookups


          //TODO: Not integrated due to performance issues on flights:
          // Error Classifier # values with low probabilities are suspicious

          def is_value_with_low_counts = udf {
            (numOfTuples: Long, columnDistinctVals: Int, value: String, valuesWithCounts: Map[String, Int]) => {

              var result = DOES_NOT_APPLY
              //todo: ValuesWithCounts are not optimal for flights

              if (numOfTuples == columnDistinctVals) result = DOES_NOT_APPLY
              else {
                if (valuesWithCounts.contains(value)) {
                  val counts: Int = valuesWithCounts.getOrElse(value, 0)
                  result = if (counts > 1) CLEAN else ERROR
                } else result = DOES_NOT_APPLY
              }
              result
            }
          }

          //Error Classifier: 6 # misfielded values
          //analysing the pattern length distribution and selecting the trimmed distribution
          //if the value is inside then clean otherwise error
          //if number of distinct pattern length <=3 then does-not-apply
          /**
            *
            * param value        :String the cell value
            * param valuesLength : Seq[Int] the set of the trimmed distribution (threshold 10%) of values pattern length
            */
          def is_value_pattern_length_within_trimmed_distr = udf {
            (value: String, valuesLength: Seq[Int]) => {
              var result = DOES_NOT_APPLY

              if (value == null) result = DOES_NOT_APPLY
              else {
                result = if (valuesLength.contains(value.size)) DOES_NOT_APPLY //here: we cannot say anything about the CLEAN
                else ERROR
              }


              result
            }
          }

          //end: misfielded values

          //Error Classifier #7 valid ssn datatype: matches regex \d{3}-\d{2}-\d{4}

          def is_valid_ssn = udf {
            (attrName: String, value: String) => {
              var result: Int = DOES_NOT_APPLY

              if (value != null) {
                val dataType: String = dataTypesDictionary.getOrElse(attrName, "")
                result = dataType match {
                  case "Social Security Number" =>
                    //if (value.matches(VALID_SSN)) CLEAN else ERROR //todo: shall be this. Hack!
                    if (value.matches(VALID_NUMBER)) CLEAN else ERROR
                  case _ => DOES_NOT_APPLY
                }
              }
              result
            }
          }

          //end: valid ssn


          //Error Classifier # Supported data types
          //https://docs.trifacta.com/display/PE/Supported+Data+Types

          //Error Classifier # Spell checker for the text attributes

          //Error Classifier # For inter-column dependencies
          /**
            * Deepdive format
            * #FD1: zip -> state
            * error_candidate(t1, 6, s1, 1):- initvalue(t1, 7, z1),
            * initvalue(t2, 7, z2),
            * initvalue(t1, 6, s1),
            * initvalue(t2, 6, s2),
            * [t1!=t2, z1=z2, s1!=s2].
            */

          /**
            * ################ Final Matrix ##################
            */
          //gathering matrix with all error classifier results
          val ec_missing_value = "ec-missing-value"
          val ec_default_value = "ec-default-value"
          val ec_top_value = "ec-top-value"
          val ec_valid_number = "ec-valid-number"
          val ec_cardinality_vio = "ec-cardinality-vio"
          val ec_lookup = "ec-lookup-attr"
          // val ec_low_counts = "ec-low-counts-suspicious"
          val ec_pattern_length_within_trimmed_dist = "ec-pattern-value"
          val ec_valid_ssn = "ec-valid-ssn"

          val metadataClassifiersNames = Seq(
            ec_missing_value,
            ec_default_value,
            ec_top_value,
            ec_valid_number,
            ec_cardinality_vio,
            ec_lookup /*,
            ec_low_counts*/
            , ec_pattern_length_within_trimmed_dist
            , ec_valid_ssn
          )
          val allMetadataBasedClassifiers: Seq[Column] = metadataClassifiersNames.map(colName => col(colName))


          val matrixWithECsFromMetadataDF: DataFrame = flatWithMetadataDF
            .withColumn(ec_missing_value, UDF.identify_missing(flatWithMetadataDF("dirty-value").isNull))
            .withColumn(ec_default_value,
              identify_default_values(flatWithMetadataDF("attrName"), flatWithMetadataDF("dirty-value")))
            .withColumn(ec_top_value, is_top_value(flatWithMetadataDF("dirty-value"), flatWithMetadataDF("attrName"), flatWithMetadataDF("top10")))
            .withColumn(ec_valid_number, is_valid_number(flatWithMetadataDF("attrName"), flatWithMetadataDF("dirty-value")))
            .withColumn(ec_cardinality_vio,
              when(flatWithMetadataDF(schema.getRecID).isin(allTuplesViolatingCardinality: _*), lit(ERROR))
                .otherwise(lit(DOES_NOT_APPLY)))
            .withColumn(ec_lookup, is_valid_by_lookup(flatWithMetadataDF("attrName"), flatWithMetadataDF(schema.getRecID)))
            /*.withColumn(ec_low_counts,
              is_value_with_low_counts(flatWithMetadataDF("number of tuples"),
                flatWithMetadataDF("distinct-vals-count"),
                flatWithMetadataDF("dirty-value"),
                flatWithMetadataDF("values-with-counts")))*/
            .withColumn(ec_pattern_length_within_trimmed_dist,
            is_value_pattern_length_within_trimmed_distr(flatWithMetadataDF("dirty-value"), flatWithMetadataDF("pattern-length-dist-10")))
            .withColumn(ec_valid_ssn, is_valid_ssn(flatWithMetadataDF("attrName"), flatWithMetadataDF("dirty-value")))




          /* create Matrix for persistence */
          //
          //val cols = Seq("attrName",
          //            "label",
          //            ec_missing_value,
          //            ec_default_value,
          //            ec_top_value,
          //            ec_valid_number,
          //            ec_cardinality_vio,
          //            ec_lookup,
          //            ec_pattern_length_within_trimmed_dist,
          //            ec_valid_ssn)

          //          val matrixWithClassifiersResult: DataFrame = matrixWithECsFromMetadataDF
          //            .select(schema.getRecID, cols: _*)
          //
          //          val homeDir: String = applicationConfig.getString(s"home.dir.$dataset")
          //
          //          WriterUtil.persistCSV(matrixWithClassifiersResult, s"$homeDir/tmp-matrix")
          //
          /* end: matrix for persistence */

          /* Create matrix for the Dawid-Skene model to discover true item states/effects from multiple noisy measurements
          * for details see here: http://aclweb.org/anthology/Q14-1025 */
          //          val classifierCols = Seq(ec_missing_value,
          //            ec_default_value,
          //            ec_top_value,
          //            ec_valid_number,
          //            ec_cardinality_vio,
          //            ec_lookup,
          //            ec_pattern_length_within_trimmed_dist,
          //            ec_valid_ssn)
          //
          //
          //          val dawidSkeneModelDF: DataFrame = DawidSkeneModel()
          //            .onDataset(dataset)
          //            .onDataFrame(matrixWithECsFromMetadataDF)
          //            .onColumns(classifierCols)
          //            .createModel()
          //
          //
          //          val homeDir: String = applicationConfig.getString(s"home.dir.$dataset")
          //          WriterUtil.persistCSV(dawidSkeneModelDF, s"$homeDir/dawidSkeneModel-matrix")
          /* end: matrix for the Dawid-Skene model */


          /* Aggregation strategies */
          val majority_voter = "majority-vote"
          val min_1_col = "min-1"
          val evaluationMatrixDF: DataFrame = matrixWithECsFromMetadataDF
            .withColumn(majority_voter, UDF.majority_vote(array(allMetadataBasedClassifiers: _*)))
            .withColumn(min_1_col, UDF.min_1(array(allMetadataBasedClassifiers: _*)))

          /*//1-st aggregation: majority voting*/
          val majorityVoterDF: DataFrame = FormatUtil
            .getPredictionAndLabelOnIntegers(evaluationMatrixDF, majority_voter)
          val eval_majority_voter: Eval = F1.evalPredictionAndLabels_TMP(majorityVoterDF)
          eval_majority_voter.printResult(s"majority voter for $dataset:")

          /*//2-nd aggregation: min-1*/
          val min1DF: DataFrame = FormatUtil
            .getPredictionAndLabelOnIntegers(evaluationMatrixDF, min_1_col)
          val eval_min_1: Eval = F1.evalPredictionAndLabels_TMP(min1DF)
          eval_min_1.printResult(s"min-1 for $dataset")
          /* end: Aggregation strategies */

        })


      }
    }
  }
}


