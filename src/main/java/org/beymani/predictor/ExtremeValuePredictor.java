/*
 * beymani: Outlier and anamoly detection 
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

package org.beymani.predictor;

import java.io.IOException;
import java.util.Map;

import org.beymani.util.OutlierScoreAggregator;
import org.chombo.util.BasicUtils;

/**
 * @author pranab
 *
 */
public class ExtremeValuePredictor extends ZscorePredictor {

	/**
	 * @param config
	 * @param idOrdinalsParam
	 * @param attrListParam
	 * @param fieldDelimParam
	 * @param attrWeightParam
	 * @param statsFilePathParam
	 * @param seasonalParam
	 * @param hdfsFileParam
	 * @param scoreThresholdParam
	 * @param expConstParam
	 * @throws IOException
	 */
	public ExtremeValuePredictor(Map<String, Object> config,String idOrdinalsParam, String attrListParam,
			String fieldDelimParam, String attrWeightParam,String statsFilePathParam, String seasonalParam,
			String hdfsFileParam, String scoreThresholdParam,String expConstParam, String ignoreMissingStatParam,
			String scoreAggggregationStrtaegyParam) throws IOException {
		super(config, idOrdinalsParam, attrListParam, fieldDelimParam, attrWeightParam,
				statsFilePathParam, seasonalParam, hdfsFileParam, scoreThresholdParam,
				expConstParam, ignoreMissingStatParam, scoreAggggregationStrtaegyParam);
	}

	/* (non-Javadoc)
	 * @see org.beymani.predictor.ZscorePredictor#execute(java.lang.String[], java.lang.String)
	 */
	@Override
	public double execute(String[] items, String compKey) {
		double score = 0;
		OutlierScoreAggregator scoreAggregator = new OutlierScoreAggregator(attrWeights.length, attrWeights);
		double thisScore = 0;
		for (int ord  :  attrOrdinals) {
			double val = Double.parseDouble(items[ord]);
			double d = 0;
			double e = 0;
			if (null != idOrdinals) {
				if (statsManager.statsExists(compKey, ord)) {
					d = Math.abs( val - statsManager.getMean(compKey,ord));
					e = Math.exp(-d / statsManager.getStdDev(compKey, ord));
					thisScore  = Math.exp(-e);
					scoreAggregator.addScore(thisScore);
				} else {
					scoreAggregator.addScore();
				}
			} else {
				d = Math.abs( val - statsManager.getMean(ord));
				e = Math.exp(-d / statsManager.getStdDev(ord));
				thisScore  = Math.exp(-e);
				scoreAggregator.addScore(thisScore);
			}
		}
		//aggregate score	
		score = getAggregateScore(scoreAggregator);
		
		//exponential normalization
		if (expConst > 0) {
			score = BasicUtils.expScale(expConst, score);
		}
		
		scoreAboveThreshold = score > scoreThreshold;
		return score;
	}
	
}
