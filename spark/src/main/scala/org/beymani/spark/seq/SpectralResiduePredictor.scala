/*
 * beymani-spark: Outlier and anamoly detection 
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.beymani.spark.seq

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.SparkContext
import org.beymani.spark.common.OutlierUtility
import org.chombo.spark.common.GeneralUtility
import org.chombo.spark.common.JobConfiguration
import org.chombo.spark.common.Record
import org.chombo.util.BasicUtils
import org.hoidla.window.FastFourierTransformWindow
import org.hoidla.analyze.FastFourierTransform
import org.chombo.math.MathUtils

/**
 * Anomaly detection in sequence data based on spectral residue
 * @author pranab
 *
 */
object SpectralResiduePredictor extends JobConfiguration with GeneralUtility with OutlierUtility {
  
   /**
   * @param args
   * @return
   */
   def main(args: Array[String]) {
	   val appName = "spectralResiduePredictor"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	   //configuration params
	   val fieldDelimIn = appConfig.getString("field.delim.in")
	   val fieldDelimOut = appConfig.getString("field.delim.out")
	   val precision = getIntParamOrElse(appConfig, "output.precision", 3)
	   val keyFieldOrdinals = toOptionalIntArray(getOptionalIntListParam(appConfig, "id.fieldOrdinals"))
	   val attrOrds = BasicUtils.fromListToIntArray(getMandatoryIntListParam(appConfig, "attr.ordinals"))
	   val attrOrdsList = attrOrds.toList
	   val seqFieldOrd = getMandatoryIntParam(appConfig, "seq.fieldOrd", "missing seq field ordinal")
	   val thresholdNorm = getOptionalDoubleParam(appConfig, "score.thresholdNorm")
	   val expConst = getDoubleParamOrElse(appConfig, "exp.const", 1.0)	 
	   val attWeightList = getMandatoryDoubleListParam(appConfig, "attr.weights", "missing attribute weights")
	   val attrWeights = BasicUtils.fromListToDoubleArray(attWeightList)
	   val scoreThreshold = getMandatoryDoubleParam(appConfig, "score.threshold", "missing score threshold")	
	   val windowSize = getIntParamOrElse(appConfig, "window.size", 3)
	   val movAvWindowSize = getIntParamOrElse(appConfig, "fft.ma.window.size", 5)
	   val ifftMovAvWindowSize = getIntParamOrElse(appConfig, "ifft.ma.window.size", 3)
	   
	   val debugOn = appConfig.getBoolean("debug.on")
	   val saveOutput = appConfig.getBoolean("save.output")

	   val keyLen = getOptinalArrayLength(keyFieldOrdinals, 1)
	   
	   //input
	   val data = sparkCntxt.textFile(inputPath)
	   
	   val keyedData = getKeyedValueWithSeq(data, fieldDelimIn, keyLen, keyFieldOrdinals, seqFieldOrd)
	   
	   //records with tag and score
	   val allTaggedData = keyedData.groupByKey.flatMap(v => {
	     val key = v._1
	     val keyStr = key.toString
	     val values = v._2.toList.sortBy(v => v.getLong(0))
	     val size = values.length
	   
	     //window
	     var windows = Map[Int, FastFourierTransformWindow]()
	     attrOrdsList.foreach(i => {
	       val window = new FastFourierTransformWindow(windowSize)
	       windows += (i -> window)
	     })
	     
	     //all scores
	     var allScores = Map[Int, ArrayBuffer[Double]]()
	     values.foreach(v => {
	       val line = v.getString(1)
	       val items = BasicUtils.getTrimmedFields(line, fieldDelimIn)
	       attrOrdsList.foreach(i => {
	         val quant = items(i).toDouble
	         val window = windows.get(i).get
	         window.add(quant)
	         if (window.isProcessed()) {
	           val ouScores = getOutlierScore(window, movAvWindowSize, ifftMovAvWindowSize)
	           val attrScores = allScores.get(i).get
	           attrScores ++=  ouScores
	         }
	       })
	     })
	     
	     //agggregate attr scores
	     values.zipWithIndex.map(r => {
	       val rec = r._1
	       val i = r._2
	       val recScores = attrOrdsList.map(a => {
	         val attrScores =  allScores.get(a).get
	         MathUtils.expScale(expConst, attrScores(i));
	       }).toArray
	       val aggScore = MathUtils.weightedAverage(recScores, attrWeights)
	       val marker = if (aggScore > scoreThreshold) "O"  else "N"
	       val line = rec.getString(1)
	       line + fieldDelimOut + BasicUtils.formatDouble(aggScore, precision) + fieldDelimOut + marker 
	     })
	   })
	   
	   if (debugOn) {
       val records = allTaggedData.collect
       records.slice(0, 100).foreach(r => println(r))
     }
	   
	   if(saveOutput) {	   
	     allTaggedData.saveAsTextFile(outputPath) 
	   }	 
	   
   }
   
  /**
 	* @param window
 	* @param movAvWindowSize
 	* @return
 	*/
  def getOutlierScore(window:FastFourierTransformWindow, movAvWindowSize:Int, ifftMovAvWindowSize:Int): Array[Double] = {
	   val amps = window.getAmp()
	   val phases = window.getPhase()
     val lamps = amps.map(v => Math.log(v))
     val avLamps = MathUtils.movingAverage(lamps, movAvWindowSize, true) 
     var res = MathUtils.subtractVector(lamps, avLamps)
     res = res.map(v => Math.exp(v))
     val f = FastFourierTransform.createComplex(res, phases)
     val ix = FastFourierTransform.ifft(f)
     val iAmp = FastFourierTransform.findAmp(ix)
     val iAmpNeighborAv = MathUtils.movingAverage(iAmp, ifftMovAvWindowSize, false)
     val ouScore = MathUtils.subtractVector(iAmp, iAmpNeighborAv)
     return ouScore
   }
  
}