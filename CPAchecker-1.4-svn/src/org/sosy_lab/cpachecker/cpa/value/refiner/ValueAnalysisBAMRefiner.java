/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cpa.value.refiner;

import java.util.Collection;

import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.cpachecker.core.CounterexampleInfo;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.ARGReachedSet;
import org.sosy_lab.cpachecker.cpa.bam.AbstractBAMBasedRefiner;
import org.sosy_lab.cpachecker.exceptions.CPAException;


public class ValueAnalysisBAMRefiner extends AbstractBAMBasedRefiner {

  private ValueAnalysisRefiner refiner;

  protected ValueAnalysisBAMRefiner(ConfigurableProgramAnalysis pCpa) throws InvalidConfigurationException {
    super(pCpa);
    refiner = ValueAnalysisRefiner.create(pCpa);
  }

  public static AbstractBAMBasedRefiner create(ConfigurableProgramAnalysis pCpa) throws InvalidConfigurationException {
    return new ValueAnalysisBAMRefiner(pCpa);
  }

  @Override
  protected CounterexampleInfo performRefinement0(ARGReachedSet pReached, ARGPath pPath) throws CPAException,
      InterruptedException {
    CounterexampleInfo refineResult = refiner.performRefinement(pReached, pPath);
    if (!refineResult.isSpurious()) {
      assert (refiner.isErrorPathFeasible(pPath)) : "not spurious must imply feasible:" + pPath;
      //throw new RefinementFailedException(RefinementFailedException.Reason.RepeatedCounterexample, null);
    }

    return refineResult;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    refiner.collectStatistics(pStatsCollection);
  }
}
