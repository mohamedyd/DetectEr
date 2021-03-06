package de.model.logistic.regression

/**
  * Created by visenger on 16/02/17.
  */
case class TrainData(regParam: Double,
                     elasticNetParam: Double,
                     bestThreshold: Double,
                     coefficients: Array[Double],
                     intercept: Double,
                     maxFMeasure: Double,
                     areaUnderRoc: Double,
                     precision: Double,
                     recall: Double,
                     f1: Double) {
  override def toString: String = {

    s"""TRAIN MODEL:
       |regParam: $regParam,
       |elasticNetParam: $elasticNetParam,
       |bestThreshold: $bestThreshold,
       |maxFMeasure: $maxFMeasure,
       |AreaUnderRoc: $areaUnderRoc,
       |ModelCoefficients: ${coefficients.mkString(",")},
       |ModelIntercept: $intercept""".stripMargin
  }

  def createModelFormula(ind: Int): String = {
    var i = 0
    val function = coefficients.map(c => {
      val idx = {
        i += 1;
        i;
      }
      if (c < 0) s"(${c})t_{$idx}" else s"${c}t_{$idx}"
    }).mkString(" + ")
    //s"""P(err)=\\frac{1}{1+\\exp ^{-($modelIntercept+$function)}}"""
    s""" y_{$ind}=$intercept+$function """
  }


}




