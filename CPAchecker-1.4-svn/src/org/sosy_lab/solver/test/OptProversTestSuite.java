package org.sosy_lab.solver.test;

import static com.google.common.truth.Truth.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.TestLogManager;
import org.sosy_lab.common.rationals.Rational;
import org.sosy_lab.solver.AssignableTerm.Variable;
import org.sosy_lab.solver.Model;
import org.sosy_lab.solver.TermType;
import org.sosy_lab.solver.api.BooleanFormula;
import org.sosy_lab.solver.api.BooleanFormulaManager;
import org.sosy_lab.solver.api.FormulaManager;
import org.sosy_lab.solver.api.NumeralFormula;
import org.sosy_lab.solver.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.solver.api.NumeralFormula.RationalFormula;
import org.sosy_lab.solver.api.NumeralFormulaManager;
import org.sosy_lab.solver.api.OptEnvironment;
import org.sosy_lab.solver.api.OptEnvironment.OptStatus;
import org.sosy_lab.solver.mathsat5.Mathsat5FormulaManager;
import org.sosy_lab.solver.z3.Z3FormulaManager;

import com.google.common.collect.ImmutableList;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    OptProversTestSuite.Mathsat5MaximizationTest.class,
    OptProversTestSuite.Z3MaximizationTest.class,
})
public class OptProversTestSuite {

  public static class Z3MaximizationTest extends OptimizationTest {

    @Override
    public FormulaManager getFormulaManager(
        LogManager logger,
        Configuration config,
        ShutdownNotifier pShutdownNotifier
    ) throws InvalidConfigurationException {
      return Z3FormulaManager.create(
          logger, config, pShutdownNotifier, null, 42, false, false
      );

    }
  }

  public static class Mathsat5MaximizationTest extends OptimizationTest {

    @Override
    public FormulaManager getFormulaManager(
        LogManager logger,
        Configuration config,
        ShutdownNotifier pShutdownNotifier
    ) throws InvalidConfigurationException {
      return Mathsat5FormulaManager.create(
          logger, config, pShutdownNotifier, null, 42, false, false
      );
    }
  }

  public static abstract class OptimizationTest {

    private FormulaManager mgr;
    private NumeralFormulaManager<NumeralFormula, RationalFormula> rfmgr;
    private NumeralFormulaManager<IntegerFormula, IntegerFormula> ifmgr;
    private BooleanFormulaManager bfmgr;

    protected abstract FormulaManager getFormulaManager(
        LogManager logger, Configuration config, ShutdownNotifier pShutdownNotifier
    ) throws InvalidConfigurationException;

    @Before
    public void loadLibrary() throws Exception {
      Configuration config = Configuration.builder().setOption(
          "solver.mathsat5.loadOptimathsat5", "true"
      ).build();

      LogManager logger = TestLogManager.getInstance();
      ShutdownNotifier shutdownNotifier = ShutdownNotifier.create();

      mgr = getFormulaManager(logger, config, shutdownNotifier);
      rfmgr =  mgr.getRationalFormulaManager();
      ifmgr =  mgr.getIntegerFormulaManager();
      bfmgr =  mgr.getBooleanFormulaManager();
    }

    @After
    public void free() throws Exception {
      if (mgr instanceof AutoCloseable) {
        ((AutoCloseable)mgr).close();
      }
    }

    @Test
    public void testUnbounded() throws Exception {
      try (OptEnvironment prover = mgr.newOptEnvironment()) {
        RationalFormula x, obj;
        x = rfmgr.makeVariable("x");
        obj = rfmgr.makeVariable("obj");
        List<BooleanFormula> constraints = ImmutableList.of(
            rfmgr.greaterOrEquals(x, rfmgr.makeNumber("10")),
            rfmgr.equal(x, obj)
        );
        prover.addConstraint(bfmgr.and(constraints));
        int handle = prover.maximize(obj);
        prover.check();
        Assert.assertTrue(!prover.upper(handle, Rational.ZERO).isPresent());
      }
    }

    @Test public void testUnfeasible() throws Exception {
      try (OptEnvironment prover = mgr.newOptEnvironment()) {
        RationalFormula x, y;
        x = rfmgr.makeVariable("x");
        y = rfmgr.makeVariable("y");
        List<BooleanFormula> constraints = ImmutableList.of(
            rfmgr.lessThan(x, y),
            rfmgr.greaterThan(x, y)
        );
        prover.addConstraint(bfmgr.and(constraints));
        prover.maximize(x);
        OptStatus response = prover.check();
        Assert.assertEquals(OptStatus.UNSAT,
            response);
      }
    }

    @Test public void testOptimal() throws Exception {
      try (OptEnvironment prover = mgr.newOptEnvironment()) {

        IntegerFormula x, y, obj;
        x = ifmgr.makeVariable("x");
        y = ifmgr.makeVariable("y");
        obj = ifmgr.makeVariable("obj");

      /*
        int x, y, obj
        x <= 10
        y <= 15
        obj = x + y
        x - y >= 1
       */
        List<BooleanFormula> constraints = ImmutableList.of(
            ifmgr.lessOrEquals(x, ifmgr.makeNumber(10)),
            ifmgr.lessOrEquals(y, ifmgr.makeNumber(15)),
            ifmgr.equal(obj, ifmgr.add(x, y)),
            ifmgr.greaterOrEquals(ifmgr.subtract(x, y), ifmgr.makeNumber(1))
        );

        prover.addConstraint(bfmgr.and(constraints));
        int handle = prover.maximize(obj);

        // Maximize for x.
        OptStatus response = prover.check();

        Assert.assertEquals(OptStatus.OPT, response);

        // Check the value.
        Assert.assertEquals(Rational.ofString("19"), prover.upper(handle, Rational.ZERO).get());

        Model model = prover.getModel();
        BigInteger xValue =
            (BigInteger)model.get(new Variable("x", TermType.Integer));
        BigInteger objValue =
            (BigInteger)model.get(new Variable("obj", TermType.Integer));
        BigInteger yValue =
            (BigInteger)model.get(new Variable("y", TermType.Integer));

        assertThat(objValue).isEqualTo(BigInteger.valueOf(19));
        assertThat(xValue).isEqualTo(BigInteger.valueOf(10));
        assertThat(yValue).isEqualTo(BigInteger.valueOf(9));

      }
    }

    @Test public void testSwitchingObjectives() throws Exception {
      try (OptEnvironment prover = mgr.newOptEnvironment()) {
        RationalFormula x, y, obj;
        x = rfmgr.makeVariable("x");
        y = rfmgr.makeVariable("y");
        obj = rfmgr.makeVariable("obj");

      /*
        real x, y, obj
        x <= 10
        y <= 15
        obj = x + y
        x - y >= 1
       */
        List<BooleanFormula> constraints = ImmutableList.of(
            rfmgr.lessOrEquals(x, rfmgr.makeNumber(10)),
            rfmgr.lessOrEquals(y, rfmgr.makeNumber(15)),
            rfmgr.equal(obj, rfmgr.add(x, y)),
            rfmgr.greaterOrEquals(rfmgr.subtract(x, y), rfmgr.makeNumber(1))
        );
        prover.addConstraint(bfmgr.and(constraints));
        OptStatus response;

        prover.push();

        int handle = prover.maximize(obj);
        response = prover.check();
        assertThat(response).isEqualTo(OptEnvironment.OptStatus.OPT);
        assertThat(prover.upper(handle, Rational.ZERO).get()).isEqualTo(Rational.ofString("19"));

        prover.pop();
        prover.push();

        handle = prover.maximize(x);
        response = prover.check();
        assertThat(response).isEqualTo(OptEnvironment.OptStatus.OPT);
        assertThat(prover.upper(handle, Rational.ZERO).get()).isEqualTo(Rational.ofString("10"));

        prover.pop();
        prover.push();

        handle = prover.maximize(rfmgr.makeVariable("y"));
        response = prover.check();
        assertThat(response).isEqualTo(OptEnvironment.OptStatus.OPT);
        assertThat(prover.upper(handle, Rational.ZERO).get()).isEqualTo(Rational.ofString("9"));

        prover.pop();
      }
    }
}
}
