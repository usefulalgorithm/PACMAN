package org.sosy_lab.cpachecker.cpa.policyiteration;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;

import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.UniqueIdGenerator;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.rationals.Rational;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression.BinaryOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpressionBuilder;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.core.algorithm.invariants.InvariantGenerator;
import org.sosy_lab.cpachecker.core.interfaces.FormulaReportingState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustmentResult;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.cpa.loopstack.LoopstackState;
import org.sosy_lab.cpachecker.cpa.policyiteration.PolicyIterationStatistics.TemplateUpdateEvent;
import org.sosy_lab.cpachecker.cpa.policyiteration.Template.Kind;
import org.sosy_lab.cpachecker.cpa.policyiteration.ValueDeterminationManager.ValueDeterminationConstraints;
import org.sosy_lab.cpachecker.cpa.policyiteration.congruence.CongruenceManager;
import org.sosy_lab.cpachecker.cpa.policyiteration.congruence.CongruenceState;
import org.sosy_lab.cpachecker.cpa.policyiteration.polyhedra.PolyhedraWideningManager;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.solver.SolverException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.LoopStructure;
import org.sosy_lab.solver.AssignableTerm;
import org.sosy_lab.cpachecker.util.predicates.Solver;
import org.sosy_lab.solver.api.BooleanFormula;
import org.sosy_lab.solver.api.BooleanFormulaManager;
import org.sosy_lab.solver.api.Formula;
import org.sosy_lab.solver.api.OptEnvironment;
import org.sosy_lab.cpachecker.util.predicates.interfaces.PathFormulaManager;
import org.sosy_lab.solver.api.ProverEnvironment;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormula;
import org.sosy_lab.cpachecker.util.predicates.pathformula.SSAMap;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.PointerTargetSet;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

/**
 * Main logic in a single class.
 */
@Options(prefix="cpa.stator.policy")
public class PolicyIterationManager implements IPolicyIterationManager {

  @Option(secure = true,
      description = "Call [simplify] on the formulas resulting from the C code")
  private boolean simplifyFormulas = true;

  @Option(secure = true,
      description = "Perform abstraction only at the nodes from the cut-set.")
  private boolean pathFocusing = true;

  @Option(secure = true, name = "epsilon",
      description = "Value to substitute for the epsilon")
  private Rational EPSILON = Rational.ONE;

  @Option(secure=true, description="Run naive value determination first, "
      + "switch to namespaced if it fails.")
  private boolean runHopefulValueDetermination = true;

  @Option(secure=true, description="Check target states reachability")
  private boolean checkTargetStates = true;

  @Option(secure=true,
  description="Use the optimized abstraction, which takes into the account the "
      + "previously obtained bound at the location. Interferes with:"
      + "obtaining templates using convex hull and split-sep merge configuration.")
  private boolean usePreviousBounds = true;

  @Option(secure=true, description="Any intermediate state with formula length "
      + "bigger than specified will be checked for reachability.")
  private int lengthLimitForSATCheck = 300;

  @Option(secure=true, description="Run simple congruence analysis")
  private boolean runCongruence = true;

  @Option(secure=true, description="Use syntactic check to short-circuit"
      + " val. det. and abstraction operations.")
  private boolean shortCircuitSyntactic = true;

  @Option(secure=true, description="Check whether the policy depends on the initial value")
  private boolean checkPolicyInitialCondition = true;

  @Option(secure=true, description="Remove UFs and ITEs from policies. "
      + "NOTE: Currently seems to decrease performance.")
  private boolean linearizePolicy = false;

  @Option(secure=true, description="Generate new templates using polyhedra convex hull")
  private boolean generateTemplatesUsingConvexHull = false;

  @Option(secure=true, description="Number of value determination steps allowed before widening is run."
      + " Value of '-1' runs value determination until convergence.")
  private int wideningThreshold = -1;

  @Option(secure=true, description="Use extra invariant during abstraction")
  private boolean useExtraPredicateDuringAbstraction = true;

  private final FormulaManagerView fmgr;
  private final boolean joinOnMerge;
  private final CFA cfa;
  private final PathFormulaManager pfmgr;
  private final BooleanFormulaManager bfmgr;
  private final Solver solver;
  private final LogManager logger;
  private final ShutdownNotifier shutdownNotifier;
  private final TemplateManager templateManager;
  private final ValueDeterminationManager vdfmgr;
  private final PolicyIterationStatistics statistics;
  private final FormulaLinearizationManager linearizationManager;
  private final CongruenceManager congruenceManager;
  private final PolyhedraWideningManager pwm;
  private final InvariantGenerator invariantGenerator;
  private final StateFormulaConversionManager stateFormulaConversionManager;
  private final CBinaryExpressionBuilder expressionBuilder;

  public PolicyIterationManager(
      Configuration config,
      FormulaManagerView pFormulaManager,
      CFA pCfa,
      PathFormulaManager pPfmgr,
      Solver pSolver,
      LogManager pLogger,
      ShutdownNotifier pShutdownNotifier,
      TemplateManager pTemplateManager,
      ValueDeterminationManager pValueDeterminationFormulaManager,
      PolicyIterationStatistics pStatistics,
      FormulaLinearizationManager pLinearizationManager,
      CongruenceManager pCongruenceManager,
      boolean pJoinOnMerge, PolyhedraWideningManager pPwm,
      InvariantGenerator pInvariantGenerator,
      StateFormulaConversionManager pStateFormulaConversionManager)
      throws InvalidConfigurationException {
    pwm = pPwm;
    stateFormulaConversionManager = pStateFormulaConversionManager;
    config.inject(this, PolicyIterationManager.class);
    fmgr = pFormulaManager;
    cfa = pCfa;
    pfmgr = pPfmgr;
    bfmgr = fmgr.getBooleanFormulaManager();
    solver = pSolver;
    logger = pLogger;
    shutdownNotifier = pShutdownNotifier;
    templateManager = pTemplateManager;
    vdfmgr = pValueDeterminationFormulaManager;
    statistics = pStatistics;
    linearizationManager = pLinearizationManager;
    congruenceManager = pCongruenceManager;
    joinOnMerge = pJoinOnMerge;
    invariantGenerator = pInvariantGenerator;
    expressionBuilder = new CBinaryExpressionBuilder(cfa.getMachineModel(),
        logger);

    /** Compute the cache for loops */
    ImmutableMap.Builder<CFANode, LoopStructure.Loop> loopStructureBuilder =
        ImmutableMap.builder();
    LoopStructure loopStructure1 = pCfa.getLoopStructure().get();
    for (LoopStructure.Loop l : loopStructure1.getAllLoops()) {
      for (CFANode n : l.getLoopHeads()) {
        loopStructureBuilder.put(n, l);
      }
    }
    loopStructure = loopStructureBuilder.build();
  }

  /**
   * Static caches
   */
  // Mapping from loop-heads to the associated loops.
  private final ImmutableMap<CFANode, LoopStructure.Loop> loopStructure;

  /**
   * The concept of a "location" is murky in a CPA.
   * Currently it's defined in a precision adjustment operator:
   * if we perform an adjustment, and there's already another state in the
   * same partition (something we are about to get merged with), we take their
   * locationID.
   * Otherwise, we generate a fresh one.
   */
  private final UniqueIdGenerator locationIDGenerator = new UniqueIdGenerator();

  private boolean invariantGenerationStarted = false;

  /**
   * @param pNode Initial node.
   * @return Initial state for the analysis, assuming the first node
   * is {@code pNode}.
   */
  @Override
  public PolicyState getInitialState(CFANode pNode) {
    // this is somewhat bad, because if we have an expensive
    // invariant generation procedure, it will block for
    // a considerable amount of time before the analysis can even start =(
    startInvariantGeneration(pNode);

    return PolicyAbstractedState.empty(
        pNode, SSAMap.emptySSAMap(),
        PointerTargetSet.emptyPointerTargetSet(),
        bfmgr.makeBoolean(true), stateFormulaConversionManager);
  }



  @Override
  public Collection<? extends PolicyState> getAbstractSuccessors(PolicyState oldState,
      CFAEdge edge) throws CPATransferException, InterruptedException {

    CFANode node = edge.getSuccessor();

    PolicyIntermediateState iOldState;

    if (oldState.isAbstract()) {
      iOldState = stateFormulaConversionManager.abstractStateToIntermediate(
          oldState.asAbstracted(), false);
    } else {
      iOldState = oldState.asIntermediate();
    }

    PathFormula outPath = pfmgr.makeAnd(iOldState.getPathFormula(), edge);

    if (simplifyFormulas) {
      statistics.simplifyTimer.start();
      outPath = outPath.updateFormula(fmgr.simplify(outPath.getFormula()));
      statistics.simplifyTimer.stop();
    }

    PolicyIntermediateState out = PolicyIntermediateState.of(
        node,
        outPath,
        iOldState.getGeneratingState());

    return Collections.singleton(out);
  }

  /**
   * Perform abstraction and reachability checking with precision adjustment
   * operator.
   */
  @Override
  public Optional<PrecisionAdjustmentResult> prec(PolicyState state,
      PolicyPrecision precision, UnmodifiableReachedSet states,
      AbstractState pArgState)
        throws CPAException, InterruptedException {

    final PolicyIntermediateState iState = state.asIntermediate();
    final boolean hasTargetState = Iterables.filter(
        AbstractStates.asIterable(pArgState),
        AbstractStates.IS_TARGET_STATE).iterator().hasNext();
    final boolean shouldPerformAbstraction = shouldPerformAbstraction(iState,
        pArgState);

    // Perform reachability checking, either for property states, or when the
    // formula gets too long, or before abstractions.
    if ((hasTargetState && checkTargetStates
        || (iState.getPathFormula().getLength() > lengthLimitForSATCheck)
        || shouldPerformAbstraction
      ) && isUnreachable(iState)) {

      logger.log(Level.INFO, "Returning BOTTOM state");
      logger.flush();
      return Optional.absent();
    }
    PolicyPrecision toNodePrecision = templateManager.precisionForNode(
        state.getNode());

    statistics.startAbstractionTimer();
    try {
      assert !state.isAbstract();

      PolicyState outState = state;

      // Only update precision on abstracted states.
      Precision newPrecision = shouldPerformAbstraction ?
          toNodePrecision : precision;

      // Perform the abstraction, if necessary.
      if (shouldPerformAbstraction) {

        // Formulas reported by other CPAs.
        BooleanFormula extraInvariant = extractReportedFormulas(pArgState);

        logger.log(Level.FINE, "Reported formulas: ", extraInvariant);

        List<PolicyAbstractedState> siblings =
            getSiblings(extraInvariant, states.getReached(pArgState));

        PolicyAbstractedState abstraction = performAbstraction(
            iState,
            siblings,
            toNodePrecision,
            extraInvariant
        );
        logger.log(Level.FINE, ">>> Abstraction produced a state: ",
            abstraction);

        if (!joinOnMerge && !siblings.isEmpty()) {
          // Run value determination inside precision adjustment if the abstract
          // states are not joined.
          logger.log(Level.FINE,  "Emulating value determination");
          outState = joinAbstractedStates(abstraction,
              siblings.iterator().next().getLatestVersion(), precision);
          for (PolicyAbstractedState sibling : siblings) {
            sibling.setNewVersion(outState.asAbstracted());
          }
        } else {
          outState = abstraction;
        }
      }

      return Optional.of(PrecisionAdjustmentResult.create(
          outState,
          newPrecision,
          PrecisionAdjustmentResult.Action.CONTINUE));
    } finally {
      statistics.stopAbstractionTimer();
    }

  }

  @Override
  public PolicyState join(PolicyState newState, PolicyState oldState,
      PolicyPrecision pPrecision)
      throws InterruptedException, CPAException, SolverException {
    Preconditions.checkState(oldState.isAbstract() == newState.isAbstract());
    PolicyState out;

    if (oldState.isAbstract()) {
      out = joinAbstractedStates(
          newState.asAbstracted(), oldState.asAbstracted(), pPrecision);
    } else {
      out = joinIntermediateStates(
          newState.asIntermediate(), oldState.asIntermediate());
    }

    return out;
  }

  /**
   * At every join, update all the references to starting states to the
   * latest ones.
   */
  private PolicyIntermediateState joinIntermediateStates(
      PolicyIntermediateState newState,
      PolicyIntermediateState oldState
  ) throws InterruptedException, SolverException {

    Preconditions.checkState(newState.getNode() == oldState.getNode(),
        "PolicyCPA must run with LocationCPA");

    if (newState.isMergedInto(oldState)) {
      return oldState;
    } else if (oldState.isMergedInto(newState)) {
      return newState;
    }

    if (oldState.getPathFormula().equals(newState.getPathFormula())) {

      // Special logic for checking after the value determination:
      // if two states share the formula, there is no reason to merge the formula.
      return newState;
    }

    if (!newState.getGeneratingState().equals(oldState.getGeneratingState())) {

      // Different parents: do not merge.
      return oldState;
    }

    PathFormula newPath = newState.getPathFormula();
    PathFormula oldPath = oldState.getPathFormula();

    PathFormula mergedPath = pfmgr.makeOr(newPath, oldPath);
    if (simplifyFormulas) {
      statistics.simplifyTimer.start();
      mergedPath = mergedPath.updateFormula(
          fmgr.simplify(mergedPath.getFormula()));
      statistics.simplifyTimer.stop();
    }

    PolicyIntermediateState out = PolicyIntermediateState.of(
        newState.getNode(),
        mergedPath,
        oldState.getGeneratingState()
    );

    newState.setMergedInto(out);
    oldState.setMergedInto(out);
    return out;
  }

  private PolicyAbstractedState joinAbstractedStates(
      final PolicyAbstractedState newState,
      final PolicyAbstractedState oldState,
      final PolicyPrecision precision
  ) throws CPATransferException, InterruptedException {
    Preconditions.checkState(newState.getNode() == oldState.getNode());
    Preconditions.checkState(
        newState.getLocationID() == oldState.getLocationID());
    CFANode node = oldState.getNode();

    if (isLessOrEqualNoCheck(newState, oldState)) {

      // New state does not introduce any updates.
      return oldState;
    }

    statistics.abstractMergeCounter.add(oldState.getLocationID());
    Map<Template, PolicyBound> updated = new HashMap<>();
    Map<Template, PolicyBound> newAbstraction = new HashMap<>();

    // Pick the biggest bound, and keep the biggest trace to match.
    for (Template template : precision) {
      Optional<PolicyBound> oldValue = oldState.getBound(template);
      Optional<PolicyBound> newValue = newState.getBound(template);

      if (!newValue.isPresent() || !oldValue.isPresent()) {

        // Either is unbounded: no need to do anything.
        continue;
      }
      PolicyBound mergedBound;
      if (newValue.get().getBound().compareTo(oldValue.get().getBound()) > 0) {
        TemplateUpdateEvent updateEvent = TemplateUpdateEvent.of(
            newState.getLocationID(), template);

        if (statistics.templateUpdateCounter.count(updateEvent) ==
            wideningThreshold) {
          // Set the value to infinity if the widening threshold was reached.
          logger.log(Level.FINE, "Widening threshold for template", template,
              "at", newState.getNode(), "was reached, widening to infinity.");
          continue;
        }
        mergedBound = newValue.get();
        updated.put(template, newValue.get());

        logger.log(Level.FINE, "Updating template", template, "at",
            newState.getNode(),
            "to", newValue.get().getBound(),
            "(was: ", oldValue.get().getBound(), ")");
        statistics.templateUpdateCounter.add(updateEvent);
      } else {
        mergedBound = oldValue.get();
      }
      newAbstraction.put(template, mergedBound);
    }

    BooleanFormula newPredicate = fmgr.simplify(
        bfmgr.or(oldState.getExtraInvariant(), newState.getExtraInvariant()));

    PolicyAbstractedState merged = PolicyAbstractedState.of(
        newAbstraction, oldState.getNode(),
        congruenceManager.join(
            newState.getCongruence(), oldState.getCongruence()),
        oldState.getLocationID(),
        stateFormulaConversionManager,
        oldState.getSSA(),

        // todo: merge pointer target states [ONLY IF the new state is not coming
        // from under the loop].
        oldState.getPointerTargetSet(),
        newPredicate
    );

    if (generateTemplatesUsingConvexHull) {
      templateManager.addGeneratedTemplates(
          pwm.generateWideningTemplates(oldState, newState));
    }

    if (joinOnMerge) {
      oldState.setNewVersion(merged);
      newState.setNewVersion(merged);
    }

    PolicyAbstractedState out;
    if (!shouldPerformValueDetermination(node, updated)) {
      logger.log(Level.FINE, "Returning state with updates");
      out = merged;

    } else {
      logger.log(Level.FINE, "Running val. det.");

      ValueDeterminationConstraints constraints;
      Optional<PolicyAbstractedState> element;
      if (runHopefulValueDetermination) {
        constraints = vdfmgr.valueDeterminationFormulaCheap(
            merged, updated);
        element = performValueDetermination(
                merged, newAbstraction, updated, constraints, true);
      } else {
        element = Optional.absent();
      }

      if (!element.isPresent()) {
        constraints = vdfmgr.valueDeterminationFormula(
            merged, updated);
        out = performValueDetermination(
            merged,
            newAbstraction,
            updated,
            constraints,
            false).get();
      } else {
        out = element.get();
      }
    }

    // Set transient update pointers.
    if (joinOnMerge) {
      oldState.setNewVersion(out);
      newState.setNewVersion(out);
    }

    Verify.verify(isLessOrEqualNoCheck(newState, out),
        "Merged state should be larger than the subsumed one",
        newState, out);

    return out;
  }

  private Optional<PolicyAbstractedState> performValueDetermination(
      PolicyAbstractedState stateWithUpdates,
      Map<Template, PolicyBound> newAbstraction,
      Map<Template, PolicyBound> updated,
      ValueDeterminationConstraints valDetConstraints,
      boolean runningCheapValueDetermination
  ) throws InterruptedException, CPATransferException {
    logger.log(Level.INFO, "Value determination at node",
        stateWithUpdates.getNode());

    // Maximize for each template subject to the overall constraints.
    statistics.startValueDeterminationTimer();
    try (OptEnvironment optEnvironment = solver.newOptEnvironment()) {

      for (BooleanFormula constraint : valDetConstraints.constraints) {
        optEnvironment.addConstraint(constraint);
      }

      for (Entry<Template, PolicyBound> policyValue : updated.entrySet()) {
        shutdownNotifier.shutdownIfNecessary();
        optEnvironment.push();

        Template template = policyValue.getKey();
        Formula objective = valDetConstraints.outVars.get(template,
            stateWithUpdates.getLocationID());
        assert objective != null;
        PolicyBound existingBound = policyValue.getValue();

        int handle = optEnvironment.maximize(objective);
        BooleanFormula consistencyConstraint =
            fmgr.makeGreaterOrEqual(
                objective,
                fmgr.makeNumber(objective, existingBound.getBound()),
                true);

        optEnvironment.addConstraint(consistencyConstraint);

        OptEnvironment.OptStatus result;
        try {
          statistics.startOPTTimer();
          result = optEnvironment.check();
        } finally {
          statistics.stopOPTTimer();
        }
        if (result != OptEnvironment.OptStatus.OPT) {
          shutdownNotifier.shutdownIfNecessary();

          if (result == OptEnvironment.OptStatus.UNSAT) {
            if (!runningCheapValueDetermination) {
              throw new CPATransferException("Inconsistent value determination "
                  + "problem");
            }

            logger.log(Level.INFO, "The val. det. problem is unsat,",
                " switching to a more expensive strategy.");
            logger.flush();
            return Optional.absent();
          }
          throw new CPATransferException("Unexpected solver state");
        }

        Optional<Rational> value = optEnvironment.upper(handle, EPSILON);

        if (value.isPresent()) {
          Rational v = value.get();
          newAbstraction.put(template, existingBound.updateValue(v));
        } else {
          newAbstraction.remove(template);
        }
        optEnvironment.pop();
      }
    } catch(SolverException e){
      throw new CPATransferException("Failed maximization ", e);
    } finally{
      statistics.stopValueDeterminationTimer();
    }

    return Optional.of(stateWithUpdates.updateAbstraction(newAbstraction));
  }


  /**
   * @return Whether to perform the value determination on <code>node</code>.
   * <p/>
   * Returns true iff the <code>node</code> is a loophead and at least one of
   * the bounds in <code>updated</code> has an associated edge coming from
   * outside of the loop.
   * Note that the function returns <code>false</code> is <code>updated</code>
   * is empty.
   */
  private boolean shouldPerformValueDetermination(
      CFANode node,
      Map<Template, PolicyBound> updated) {

    if (updated.isEmpty()) {
      return false;
    }

    // At least one of updated values comes from inside the loop.
    LoopStructure.Loop l = loopStructure.get(node);
    if (l == null) {
      // NOTE: sometimes there is no loop-structure when there's
      // one self-edge.
      return true;
    }
    for (PolicyBound bound : updated.values()) {
      CFANode fromNode = bound.getPredecessor().getNode();
      if (l.getLoopNodes().contains(fromNode)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return Whether the <code>state</code> is unreachable.
   */
  private boolean isUnreachable(PolicyIntermediateState state)
      throws CPAException, InterruptedException {
    BooleanFormula startConstraints =
        stateFormulaConversionManager.getStartConstraints(state, true);

    BooleanFormula constraint = bfmgr.and(
        startConstraints, state.getPathFormula().getFormula());

    try {
      statistics.startCheckSATTimer();
      return solver.isUnsat(constraint);
    } catch (SolverException e) {
      throw new CPATransferException("Failed solving", e);
    } finally {
      statistics.stopCheckSATTimer();
    }
  }

  /**
   * Perform the abstract operation on a new state
   *
   * @param state State to abstract
   * @return Abstracted state if the state is reachable, empty optional
   * otherwise.
   */
  private PolicyAbstractedState performAbstraction(
      final PolicyIntermediateState state,
      final List<PolicyAbstractedState> otherStates,
      PolicyPrecision precision,
      BooleanFormula extraPredicate)
      throws CPAException, InterruptedException {

    logger.log(Level.FINE, "Performing abstraction at node: ", state.getNode());

    int locationID;
    if (!otherStates.isEmpty()) {

      // They should all share the same location ID.
      locationID = otherStates.iterator().next().getLocationID();
    } else {
      locationID = locationIDGenerator.getFreshId();
    }

    final PathFormula p = state.getPathFormula();

    // Linearize.
    final BooleanFormula linearizedFormula = linearizationManager.linearize(
        p.getFormula());

    // Add choice variables.
    BooleanFormula annotatedFormula = linearizationManager.annotateDisjunctions(
        linearizedFormula);

    final Map<Template, PolicyBound> abstraction = new HashMap<>();
    final BooleanFormula startConstraints =
        stateFormulaConversionManager.getStartConstraints(state, true);

    try (OptEnvironment optEnvironment = solver.newOptEnvironment()) {
      optEnvironment.addConstraint(annotatedFormula);
      optEnvironment.addConstraint(startConstraints);

      if (useExtraPredicateDuringAbstraction) {

        // Invariant from other CPAs.
        optEnvironment.addConstraint(
            fmgr.instantiate(extraPredicate, state.getPathFormula().getSsa())
        );
      }

      // Invariant from the invariant generator.
      optEnvironment.addConstraint(
          fmgr.instantiate(
              stateFormulaConversionManager.getInvariantFor(state.getNode()),
              state.getPathFormula().getSsa()
          )
      );

      Set<String> formulaVars = fmgr.extractFunctionNames(
          state.getPathFormula().getFormula(), true);
      for (Template template : precision) {
        shutdownNotifier.shutdownIfNecessary();

        // Optimize for the template subject to the
        // constraints introduced by {@code p}.
        Formula objective = templateManager.toFormula(pfmgr, fmgr, template, p);

        // We only care for a new value if it is the larger than the one
        // we currently have.
        // Skip the iteration if the previous value is already unbounded,
        // add a lemma that the new value has to be strictly larger otherwise.
        BooleanFormula prevStateConstraint = bfmgr.makeBoolean(true);
        PolicyBound prevBound = null;
        if (usePreviousBounds && !otherStates.isEmpty()) {
          PolicyAbstractedState prevState = otherStates.iterator().next()
              .getLatestVersion();
          Optional<PolicyBound> bound = prevState.getBound(template);
          if (!bound.isPresent()) {

            // Can't do better than unbounded.
            continue;
          } else {
            prevBound = bound.get();
            Rational prevValue = prevBound.getBound();

            prevStateConstraint = fmgr.makeGreaterThan(
                objective, fmgr.makeNumber(objective, prevValue), true);
          }
        }

        // Skip updates if the edge does not have any variables mentioned in the
        // template.
        if (shortCircuitSyntactic) {
          Optional<Optional<PolicyBound>> syntacticCheckResult =
              shouldPerformOptimization(state, formulaVars, template);
          if (syntacticCheckResult.isPresent()) {
            Optional<PolicyBound> inner = syntacticCheckResult.get();
            if (inner.isPresent()) {
              abstraction.put(template, inner.get());
            }
            continue;
          }
        }

        optEnvironment.push();
        optEnvironment.addConstraint(prevStateConstraint);

        logger.log(Level.FINE, "Optimizing for ", objective);
        int handle = optEnvironment.maximize(objective);

        OptEnvironment.OptStatus status;
        try {
          statistics.startOPTTimer();
          status = optEnvironment.check();
        } finally {
          statistics.stopOPTTimer();
        }

        switch (status) {
          case OPT:
            Optional<Rational> bound = optEnvironment.upper(handle, EPSILON);
            Map<AssignableTerm, Object> model = optEnvironment.getModel();

            // Lower bound on unsigned variables is at least zero.
            boolean unsignedAndLower = template.isUnsigned() &&
                (template.getKind() == Kind.NEG_LOWER_BOUND ||
                template.getKind() == Kind.NEG_SUM_LOWER_BOUND);
            if (bound.isPresent() || unsignedAndLower) {
              Rational boundValue;
              if (bound.isPresent() && unsignedAndLower) {
                boundValue = Rational.max(bound.get(), Rational.ZERO);
              } else if (bound.isPresent()){
                boundValue = bound.get();
              } else {
                boundValue = Rational.ZERO;
              }

              if (linearizePolicy) {
                annotatedFormula = linearizationManager.convertToPolicy(
                    annotatedFormula, optEnvironment);
              }

              CSimpleType templateType = getTemplateType(template);
              if (templateType.getType().isIntegerType()) {
                BigInteger maxValue = cfa.getMachineModel()
                    .getMaximalIntegerValue(templateType);
                BigInteger minValue = cfa.getMachineModel()
                    .getMinimalIntegerValue(templateType);

                // The bound obtained is larger than the highest representable
                // value, ignore it.
                if (boundValue.compareTo(Rational.ofBigInteger(maxValue)) == 1
                    || boundValue.compareTo(Rational.ofBigInteger(minValue)) == -1) {
                  logger.log(Level.FINE, "Bound too high, replacing with 'unbounded'",
                      bound);
                  break;
                }
              }

              PolicyBound policyBound = modelToPolicyBound(
                  objective, state, p, annotatedFormula, model, boundValue);
              abstraction.put(template, policyBound);
            }
            logger.log(Level.FINE, "Got bound: ", bound);
            break;

          case UNSAT:
            logger.log(Level.FINE, "Got UNSAT, previous value must be unbeatable");
            assert prevBound != null : "Got UNSAT during abstraction, no previous value supplied";

            // Use the previous bound.
            abstraction.put(template, prevBound);
            break;

          case UNDEF:
            shutdownNotifier.shutdownIfNecessary();
            throw new CPATransferException("Solver returned undefined status");
        }
        optEnvironment.pop();
      }
    } catch (SolverException e) {
      throw new CPATransferException("Solver error: ", e);
    }

    statistics.updateCounter.add(locationID);
    CongruenceState congruence;
    if (runCongruence) {
      congruence = congruenceManager.performAbstraction(
          state.getNode(), p, startConstraints
      );
    } else {
      congruence = CongruenceState.empty();
    }

    return PolicyAbstractedState.of(
            abstraction,
            state.getNode(),
            congruence,
            locationID,
            stateFormulaConversionManager,
            state.getPathFormula().getSsa(),
            state.getPathFormula().getPointerTargetSet(),
            extraPredicate
        );
  }

  private CSimpleType getTemplateType(Template t) {
    CExpression sum = null;

    // also note: there is an overall _expression_ type.
    // Wonder how that one is computed --- it actually depends on the order of
    // the operands.
    for (Entry<CIdExpression, Rational> e: t.getLinearExpression()) {
      CIdExpression expr = e.getKey();
      if (sum == null) {
        sum = expr;
      } else {
        sum = expressionBuilder.buildBinaryExpressionUnchecked(
            sum, expr, BinaryOperator.PLUS);
      }
    }
    assert sum != null;
    return (CSimpleType) sum.getExpressionType();
  }


  /**
   * Use the auxiliary variables from the {@code model} to reconstruct the
   * policy which was used for abstracting the state.
   */
  private PolicyBound modelToPolicyBound(
      Formula templateObjective,
      PolicyIntermediateState inputState,
      PathFormula inputPathFormula,
      BooleanFormula annotatedFormula,
      Map<AssignableTerm, Object> model,
      Rational bound) throws SolverException, InterruptedException {

    final BooleanFormula policyFormula = linearizationManager.enforceChoice(
        annotatedFormula, model);
    final boolean dependsOnInitial;

    if (checkPolicyInitialCondition) {
        statistics.checkIndependenceTimer.start();
        try (ProverEnvironment prover = solver.newProverEnvironment()) {

          //noinspection ResultOfMethodCallIgnored
          prover.push(policyFormula);

          //noinspection ResultOfMethodCallIgnored
          prover.push(fmgr.makeGreaterThan(
              templateObjective,
              fmgr.makeNumber(templateObjective, bound), true));
          try {
            dependsOnInitial = !prover.isUnsat();
          } finally {
            statistics.checkIndependenceTimer.stop();
            prover.pop();
          }
        }
    } else {
        dependsOnInitial = true;
    }

    PolicyAbstractedState backpointer = inputState.getGeneratingState()
        .getLatestVersion();

    Set<String> policyVars = fmgr.extractFunctionNames(policyFormula, true);
    Set<Template> dependencies;
    if (!dependsOnInitial) {
      dependencies = ImmutableSet.of();
    } else if (!shortCircuitSyntactic) {
      dependencies = templateManager.templatesForNode(backpointer.getNode());
    } else {
      dependencies = new HashSet<>();
      for (Template t : templateManager.templatesForNode(backpointer.getNode())) {
        Set<String> fVars = fmgr.extractFunctionNames(templateManager.toFormula(
            pfmgr, fmgr, t,
            stateFormulaConversionManager.getPathFormula(backpointer, fmgr,
                false)
        ), true);
        if (!Sets.intersection(fVars, policyVars).isEmpty()) {
          dependencies.add(t);
        }
      }
    }

    return PolicyBound.of(
        inputPathFormula.updateFormula(policyFormula), bound, backpointer,
        dependencies);
  }

  /**
   * @param totalState Encloses all other parallel states.
   * @return Whether to compute the abstraction when creating a new
   * state associated with <code>node</code>.
   */
  private boolean shouldPerformAbstraction(PolicyIntermediateState iState,
      AbstractState totalState) {

    if (!pathFocusing) {
      return true;
    }
    LoopstackState loopState = AbstractStates.extractStateByType(totalState,
        LoopstackState.class);

    CFANode node = iState.getNode();
    if ((node.isLoopStart() ||
        cfa.getAllLoopHeads().get().contains(node))

        // If loopstackState is available,
        // do not compute abstractions at partial unrollings.
        && (loopState == null || loopState.isLoopCounterAbstracted())
        ) {
      return true;
    }
    return false;
  }

  /** HELPER METHODS BELOW. **/

  /**
   * Perform a syntactic check on whether an abstraction is necessary on a
   * given template.
   *
   * Optional.absent() => abstraction necessary
   * Optional.of(Optional.absent()) => unbounded
   * Optional.of(Optional.of(bound)) => fixed bound
   */
  private Optional<Optional<PolicyBound>> shouldPerformOptimization(
      PolicyIntermediateState state,
      Set<String> formulaVars,
      Template pTemplate
  ) {
    PolicyAbstractedState generatingState = state.getGeneratingState().getLatestVersion();
    Set<String> templateVars = fmgr.extractFunctionNames(
        templateManager.toFormula(pfmgr, fmgr, pTemplate, state.getPathFormula()),
        true
    );

    if (!Sets.intersection(formulaVars, templateVars).isEmpty()) {
      return Optional.absent();
    }

    // Otherwise compute the bound from the previous value.
    Optional<PolicyBound> genBound = generatingState.getBound(pTemplate);
    if (!genBound.isPresent()) {
      return Optional.of(Optional.<PolicyBound>absent());
    }
    return Optional.of(Optional.of(
        PolicyBound.of(
            // todo: whether the policy should contain the extra attached
            // invariant.
            stateFormulaConversionManager
                .getPathFormula(generatingState, fmgr, true)
                .updateFormula(bfmgr.makeBoolean(true)),
            genBound.get().getBound(),
            generatingState,
            ImmutableSet.of(pTemplate)
        )
    ));
  }

  /**
   * Find the PolicyAbstractedState sibling: something about-to-be-merged
   * with the argument state.
   */
  private List<PolicyAbstractedState> getSiblings(
      BooleanFormula extraInvariant,
      Collection<AbstractState> pSiblings) {
    List<PolicyAbstractedState> out = new ArrayList<>();
    if (pSiblings.isEmpty()) {
      return out;
    }

    for (AbstractState sibling : pSiblings) {
      PolicyAbstractedState s = AbstractStates.extractStateByType(sibling,
          PolicyAbstractedState.class);
      if (s != null && s.getExtraInvariant().equals(extraInvariant)) {
        out.add(s);
      }
    }
    return out;
  }

  @Override
  public boolean adjustPrecision() {
    return templateManager.adjustPrecision();
  }

  @Override
  public void adjustReachedSet(ReachedSet pReachedSet) {
    pReachedSet.clear();
  }

  @Override
  public boolean isLessOrEqual(PolicyState state1, PolicyState state2) {
    try {
      statistics.comparisonTimer.start();
      boolean out = isLessOrEqualNoCheck(state1, state2);
      Verify.verify(!(state1.isAbstract() && joinOnMerge && !out),
          "In the join config '<=' check on abstracted states should always return 'true'",
          state1, state2);
      return out;
    } finally {
      statistics.comparisonTimer.stop();
    }
  }

  private boolean isLessOrEqualNoCheck(PolicyState state1, PolicyState state2) {
    Preconditions.checkState(state1.isAbstract() == state2.isAbstract());

    if (state1.isAbstract()) {
      PolicyAbstractedState aState1 = state1.asAbstracted();
      PolicyAbstractedState aState2 = state2.asAbstracted();

      if (!congruenceManager.isLessOrEqual(aState1.getCongruence(),
          aState2.getCongruence())) {
        return false;
      }

      for (Entry<Template, PolicyBound> e : aState2) {
        Template t = e.getKey();
        PolicyBound bound = e.getValue();

        Optional<PolicyBound> otherBound = aState1.getBound(t);
        if (!otherBound.isPresent()
            || otherBound.get().getBound().compareTo(bound.getBound()) >= 1) {
          return false;
        }
      }
      return true;
    } else {
      PolicyIntermediateState iState1 = state1.asIntermediate();
      PolicyIntermediateState iState2 = state2.asIntermediate();
      return iState1.getPathFormula().getFormula().equals(
          iState2.getPathFormula().getFormula()
      ) && iState1.getGeneratingState().equals(iState2.getGeneratingState())
          || iState1.isMergedInto(iState2);
    }
  }

  private BooleanFormula extractReportedFormulas(AbstractState state) {
    BooleanFormula result = bfmgr.makeBoolean(true);
    for (FormulaReportingState s : AbstractStates.asIterable(state).filter(FormulaReportingState.class)) {
      if (s instanceof PolicyAbstractedState) {

        // Do not use our own invariants.
        continue;
      }
      result = bfmgr.and(result, s.getFormulaApproximation(fmgr, pfmgr));
    }
    return result;
  }

  private void startInvariantGeneration(CFANode pNode) {
    if (!invariantGenerationStarted) {
      invariantGenerator.start(pNode);
    }
    invariantGenerationStarted = true;
  }
}
