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
package org.sosy_lab.cpachecker.cpa.predicate.persistence;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.sosy_lab.common.Appenders;
import org.sosy_lab.common.Pair;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.predicates.AbstractionPredicate;
import org.sosy_lab.solver.api.BooleanFormula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;


public class PredicatePersistenceUtils {

  public static enum PredicateDumpFormat {PLAIN, SMTLIB2}
  public static final Splitter LINE_SPLITTER = Splitter.on('\n').omitEmptyStrings();
  public static final Joiner LINE_JOINER = Joiner.on('\n');

  public static class PredicateParsingFailedException extends CPAException {
    private static final long serialVersionUID = 5034288100943314517L;

    public PredicateParsingFailedException(String msg, String source, int lineNo) {
      super("Parsing failed in line " + lineNo + " of " + source + ": " + msg);
    }


    public PredicateParsingFailedException(Throwable cause, String source, int lineNo) {
      this(cause.getMessage(), source, lineNo);
      initCause(cause);
    }
  }

  public static Pair<String, List<String>> splitFormula(FormulaManagerView fmgr, BooleanFormula f) {
    StringBuilder fullString = new StringBuilder();
    Appenders.appendTo(fullString, fmgr.dumpFormula(f));
    List<String> lines = LINE_SPLITTER.splitToList(fullString);

    String formulaString;
    List<String> declarations;

    if (lines.isEmpty()) {
      if (fmgr.getBooleanFormulaManager().isTrue(f)) {
        declarations = Collections.emptyList();
        formulaString = "(assert true)";
      } else {
        throw new AssertionError();
      }
    } else {
      declarations = lines.subList(0, lines.size()-1);
      formulaString = Iterables.getLast(lines);
    }

    assert formulaString.startsWith("(assert ") && formulaString.endsWith(")") : "Unexpected formula format: " + formulaString;

    return Pair.of(formulaString, declarations);
  }

  static void writeSetOfPredicates(Appendable sb, String key,
      Collection<AbstractionPredicate> predicates,
      Map<AbstractionPredicate, String> predToString) throws IOException {
    if (!predicates.isEmpty()) {
      sb.append(key);
      sb.append(":\n");
      for (AbstractionPredicate pred : predicates) {
        sb.append(checkNotNull(predToString.get(pred)));
        sb.append('\n');
      }
      sb.append('\n');
    }
  }

  static Pair<Integer, String> parseCommonDefinitions(BufferedReader reader, String sourceIdentifier) throws PredicateParsingFailedException, IOException {
    // first, read first section with initial set of function definitions
    StringBuilder functionDefinitionsBuffer = new StringBuilder();

    int lineNo = 0;
    String currentLine;
    while ((currentLine = reader.readLine()) != null) {
      currentLine = currentLine.trim();
      lineNo++;

      if (currentLine.isEmpty()) {
        break;
      }

      if (currentLine.startsWith("//")) {
        // comment
        continue;
      }

      if (currentLine.startsWith("(") && currentLine.endsWith(")")) {
        functionDefinitionsBuffer.append(currentLine);
        functionDefinitionsBuffer.append('\n');

      } else {
        throw new PredicateParsingFailedException(currentLine + " is not a valid SMTLIB2 definition", sourceIdentifier, lineNo);
      }
    }

    return Pair.of(lineNo, functionDefinitionsBuffer.toString());
  }

}
