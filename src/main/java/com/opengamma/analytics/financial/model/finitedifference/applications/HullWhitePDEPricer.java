/**
 * Copyright (C) 2012 - present by OpenGamma Inc. and the OpenGamma group of companies
 * 
 * Please see distribution for license.
 */
package com.opengamma.analytics.financial.model.finitedifference.applications;

import java.util.Arrays;

import com.opengamma.analytics.financial.model.finitedifference.BoundaryCondition;
import com.opengamma.analytics.financial.model.finitedifference.ConvectionDiffusionPDE1DCoefficients;
import com.opengamma.analytics.financial.model.finitedifference.ConvectionDiffusionPDE1DStandardCoefficients;
import com.opengamma.analytics.financial.model.finitedifference.NeumannBoundaryCondition;
import com.opengamma.analytics.financial.model.finitedifference.PDE1DDataBundle;
import com.opengamma.analytics.financial.model.finitedifference.PDEFullResults1D;
import com.opengamma.analytics.financial.model.finitedifference.PDEGrid1D;
import com.opengamma.analytics.financial.model.finitedifference.SandBox;
import com.opengamma.analytics.financial.model.finitedifference.ThetaMethodFiniteDifference;
import com.opengamma.analytics.math.function.Function1D;
import com.opengamma.util.ArgumentChecker;

/**
 * Sets up a PDE solver to solve the Black-Scholes-Merton PDE for the price of a European or American option on a commodity using a (near) uniform grid.
 * This code should be view as an example of how to setup the PDE solver.
 */
public class HullWhitePDEPricer {

  private static final InitialConditionsProvider ICP = new InitialConditionsProvider();
  
  /*
   * Crank-Nicolson (i.e. theta = 0.5) is known to give poor results around at-the-money. This can be solved by using a short fully implicit (theta = 1.0) burn-in period.
   * Eigenvalues associated with the discontinuity in the first derivative are not damped out when theta = 0.5, but are for theta = 1.0 - the time step for this phase should be
   * such that the Crank-Nicolson (order(dt^2)) accuracy is not destroyed.
   */
  private static final boolean USE_BURNIN = true;
  private static final double BURNIN_FRACTION = 0.20;
  private static final double BURNIN_THETA = 1.0;
  private static final double MAIN_RUN_THETA = 0.5;

  private final boolean _useBurnin;
  private final double _burninFrac;
  private final double _burninTheta;
  private final double _mainRunTheta;

  /**
   * Finite difference PDE solver that uses a 'burn-in' period that consumes 20% of the time nodes (and hence the compute time) and runs with a theta of 1.0.
   * <b>Note</b> These setting are ignored if user supplies own grids and thetas.
   */
  public HullWhitePDEPricer() {
    _useBurnin = USE_BURNIN;
    _burninFrac = BURNIN_FRACTION;
    _burninTheta = BURNIN_THETA;
    _mainRunTheta = MAIN_RUN_THETA;
  }

  /**
   * All these setting are ignored if user supplies own grids and thetas
   * @param useBurnin useBurnin if true use a 'burn-in' period that consumes 20% of the time nodes (and hence the compute time) and runs with a theta of 1.0
   */
  public HullWhitePDEPricer(final boolean useBurnin) {
    _useBurnin = useBurnin;
    _burninFrac = BURNIN_FRACTION;
    _burninTheta = BURNIN_THETA;
    _mainRunTheta = MAIN_RUN_THETA;
  }

  /**
   * All these setting are ignored if user supplies own grids and thetas
   * @param useBurnin if true use a 'burn-in' period that consumes some fraction of the time nodes (and hence the compute time) and runs with a theta of 1.0
   * @param burninFrac The fraction of burn-in (ignored if useBurnin is false)
   */
  public HullWhitePDEPricer(final boolean useBurnin, final double burninFrac) {
    ArgumentChecker.isTrue(burninFrac < 0.5, "burn-in fraction too high");
    _useBurnin = useBurnin;
    _burninFrac = burninFrac;
    _burninTheta = BURNIN_THETA;
    _mainRunTheta = MAIN_RUN_THETA;
  }

  /**
   * All these setting are ignored if user supplies own grids and thetas
   * @param useBurnin if true use a 'burn-in' period that consumes some fraction of the time nodes (and hence the compute time) and runs with a different theta
   * @param burninFrac The fraction of burn-in (ignored if useBurnin is false)
   * @param burninTheta the theta to use for burnin (default is 1.0) (ignored if useBurnin is false)
   * @param mainTheta the theta to use for the main steps (default is 0.5)
   */
  public HullWhitePDEPricer(final boolean useBurnin, final double burninFrac, final double burninTheta, final double mainTheta) {
    ArgumentChecker.isTrue(burninFrac < 0.5, "burn-in fraction too high");
    ArgumentChecker.isTrue(0 <= burninTheta && burninTheta <= 1.0, "burn-in theta must be between 0 and 1.0");
    ArgumentChecker.isTrue(0 <= mainTheta && mainTheta <= 1.0, "main theta must be between 0 and 1.0");
    _useBurnin = useBurnin;
    _burninFrac = burninFrac;
    _burninTheta = burninTheta;
    _mainRunTheta = mainTheta;
  }

  /**
   * Price a European option on a commodity under the Black-Scholes-Merton assumptions (i.e. constant risk-free rate, cost-of-carry, and volatility) by using finite difference methods
   * to solve the Black-Scholes-Merton PDE. The grid is close to uniform in space (the strike and spot lie on the grid) and time<p>
   * Since a rather famous analytic formula exists for the price of European options on commodities, this is simple for test purposes
   * @param s0 The spot
   * @param k The strike
   * @param r The risk-free rate
   * @param b The cost-of-carry
   * @param t The time-to-expiry
   * @param sigma The volatility
   * @param isCall true for calls
   * @param spaceNodes Number of Space nodes
   * @param timeNodes Number of time nodes
   * @return The option price
   */
	/*
	 * public double price(final double s0, final double k, final double r, final
	 * double b, final double t, final double sigma, final boolean isCall, final int
	 * spaceNodes, final int timeNodes) { return price(s0, k, r, b, t, sigma,
	 * isCall, false, spaceNodes, timeNodes); }
	 */

  /**
   * Price a European or American option on a commodity under the Black-Scholes-Merton assumptions (i.e. constant risk-free rate, cost-of-carry, and volatility) by using
   * finite difference methods to solve the Black-Scholes-Merton PDE. The grid is close to uniform in space (the strike and spot lie on the grid) and time<p>
   * Since a rather famous analytic formula exists for the price of European options on commodities that should be used in place of this
   * @param s0 The spot
   * @param k The strike
   * @param r The risk-free rate
   * @param b The cost-of-carry
   * @param t The time-to-expiry
   * @param sigma The volatility
   * @param isCall true for calls
   * @param isAmerican true if the option is American (false for European)
   * @param spaceNodes Number of Space nodes
   * @param timeNodes Number of time nodes
   * @return The option price
   */
	/*
	 * public double price(final double s0, final double k, final double r, final
	 * double b, final double t, final double sigma, final boolean isCall, final
	 * boolean isAmerican, final int spaceNodes, final int timeNodes) {
	 * 
	 * final double mult = Math.exp(6.0 * sigma * Math.sqrt(t)); final double sMin =
	 * Math.min(0.8 * k, s0 / mult); final double sMax = Math.max(1.25 * k, s0 *
	 * mult);
	 * 
	 * // set up a near-uniform mesh that includes spot and strike final double[]
	 * fixedPoints = k == 0.0 ? new double[] {s0} : new double[] {s0, k}; final
	 * MeshingFunction xMesh = new ExponentialMeshing(sMin, sMax, spaceNodes, 0.0,
	 * fixedPoints);
	 * 
	 * PDEGrid1D[] grid; double[] theta; if (_useBurnin) { final int tBurnNodes =
	 * (int) Math.max(2, timeNodes * _burninFrac); final double tBurn = _burninFrac
	 * * t * t / timeNodes; if (tBurn >= t) { // very unlikely to hit this final int
	 * minNodes = (int) Math.ceil(_burninFrac * t); final double minFrac = timeNodes
	 * / t; throw new
	 * IllegalArgumentException("burn in period greater than total time. Either increase timeNodes to above "
	 * + minNodes + ", or reduce burninFrac to below " + minFrac); } final
	 * MeshingFunction tBurnMesh = new ExponentialMeshing(0.0, tBurn, tBurnNodes,
	 * 0.0); final MeshingFunction tMesh = new ExponentialMeshing(tBurn, t,
	 * timeNodes - tBurnNodes, 0.0); grid = new PDEGrid1D[2]; grid[0] = new
	 * PDEGrid1D(tBurnMesh, xMesh); grid[1] = new PDEGrid1D(tMesh, xMesh); theta =
	 * new double[] {_burninTheta, _mainRunTheta}; } else { grid = new PDEGrid1D[1];
	 * final MeshingFunction tMesh = new ExponentialMeshing(0, t, timeNodes, 0.0);
	 * grid[0] = new PDEGrid1D(tMesh, xMesh); theta = new double[] {_mainRunTheta};
	 * }
	 * 
	 * return price(s0, k, r, b, t, sigma, isCall, isAmerican, grid, theta); }
	 */

  /**
   * Price a European or American option on a commodity under the Black-Scholes-Merton assumptions (i.e. constant risk-free rate, cost-of-carry, and volatility) by using
   * finite difference methods to solve the Black-Scholes-Merton PDE. The spatial (spot) grid concentrates points around the spot level and ensures that
   * strike and spot lie on the grid. The temporal grid concentrates points near time-to-expiry = 0 (i.e. the start). The PDE solver uses theta = 0.5 (Crank-Nicolson)
   * unless a burn-in period is use, in which case theta = 1.0 (fully implicit) in that region.
   * @param s0 The spot
   * @param k The strike
   * @param r The risk-free rate
   * @param b The cost-of-carry
   * @param t The time-to-expiry
   * @param sigma The volatility
   * @param isCall true for calls
   * @param isAmerican true if the option is American (false for European)
   * @param spaceNodes Number of Space nodes
   * @param timeNodes Number of time nodes
   * @param beta Bunching parameter for space (spot) nodes. A value great than zero. Very small values gives a very high density of points around the spot, with the
   * density quickly falling away in both directions
   * @param lambda Bunching parameter for time nodes. $\lambda = 0$ is uniform, $\lambda > 0$ gives a high density of points near $\tau = 0$
   * @param sd The number of standard deviations from s0 to place the boundaries. Values between 3 and 6 are recommended.
   * @return The option price
   */
	/*
	 * public double price(final double s0, final double k, final double r, final
	 * double b, final double t, final double sigma, final boolean isCall, final
	 * boolean isAmerican, final int spaceNodes, final int timeNodes, final double
	 * beta, final double lambda, final double sd) {
	 * 
	 * final double sigmaRootT = sigma * Math.sqrt(t); final double mult =
	 * Math.exp(sd * sigmaRootT); final double sMin = s0 / mult; final double sMax =
	 * s0 * mult; if (sMax <= 1.25 * k) { final double minSD = Math.log(1.25 * k /
	 * s0) / sigmaRootT; throw new
	 * IllegalArgumentException("sd does not give boundaries that contain the strike. Use a minimum value of "
	 * + minSD); }
	 * 
	 * // centre the nodes around the spot final double[] fixedPoints = k == 0.0 ?
	 * new double[] {s0} : new double[] {s0, k}; final MeshingFunction xMesh = new
	 * HyperbolicMeshing(sMin, sMax, s0, spaceNodes, beta, fixedPoints);
	 * 
	 * MeshingFunction tMesh = new ExponentialMeshing(0, t, timeNodes, lambda);
	 * final PDEGrid1D[] grid; final double[] theta;
	 * 
	 * if (_useBurnin) { final int tBurnNodes = (int) Math.max(2, timeNodes *
	 * _burninFrac); final double dt = tMesh.evaluate(1) - tMesh.evaluate(0); final
	 * double tBurn = tBurnNodes * dt * dt; final MeshingFunction tBurnMesh = new
	 * ExponentialMeshing(0, tBurn, tBurnNodes, 0.0); tMesh = new
	 * ExponentialMeshing(tBurn, t, timeNodes - tBurnNodes, lambda); grid = new
	 * PDEGrid1D[2]; grid[0] = new PDEGrid1D(tBurnMesh, xMesh); grid[1] = new
	 * PDEGrid1D(tMesh, xMesh); theta = new double[] {_burninTheta, _mainRunTheta};
	 * } else { grid = new PDEGrid1D[1]; grid[0] = new PDEGrid1D(tMesh, xMesh);
	 * theta = new double[] {_mainRunTheta}; }
	 * 
	 * return price(s0, k, r, b, t, sigma, isCall, isAmerican, grid, theta); }
	 */

  /**
   * Price a European or American option on a commodity under the Black-Scholes-Merton assumptions (i.e. constant risk-free rate, cost-of-carry, and volatility) by using
   * finite difference methods to solve the Black-Scholes-Merton PDE. <b>Note</b> This is a specialist method that requires correct grid
   * set up - if unsure use another method that sets up the grid for you.
   * @param s0 The spot
   * @param k The strike
   * @param r The risk-free rate
   * @param b The cost-of-carry
   * @param t The time-to-expiry
   * @param sigma The volatility
   * @param isCall true for calls
   * @param isAmerican true if the option is American (false for European)
   * @param grid the grids. If a single grid is used, the spot must be a grid point and the strike
   * must lie in the range of the xNodes; the time nodes must start at zero and finish at t (time-to-expiry). For multiple grids,
   * the xNodes must be <b>identical</b>, and the last time node of one grid must be the same as the first time node of the next.
   * @param theta the theta to use on different grids
   * @return The option price
   */
  public PDEFullResults1D price(final double r0,  final int T,final double rMax,final double rMin, final double sigma, final double thetaHW,final double kappa,final Function1D<Double, Double> initial ,final ConvectionDiffusionPDE1DStandardCoefficients  coef, final PDEGrid1D[] grid, final double[] theta) {

    final int n = grid.length;
    ArgumentChecker.isTrue(n == theta.length, "#theta does not match #grid");

    // TODO allow change in grid size and remapping (via spline?) of nodes
    // ensure the grids are consistent
    final double[] xNodes = grid[0].getSpaceNodes();
    ArgumentChecker.isTrue(grid[0].getTimeNode(0) == 0.0, "time nodes not starting from zero");
    ArgumentChecker.isTrue(Double.compare(grid[n - 1].getTimeNode(grid[n - 1].getNumTimeNodes() - 1), T) == 0, "time nodes not ending at t");
    for (int ii = 1; ii < n; ii++) {
      ArgumentChecker.isTrue(Arrays.equals(grid[ii].getSpaceNodes(), xNodes), "different xNodes not supported");
      ArgumentChecker.isTrue(Double.compare(grid[ii - 1].getTimeNode(grid[ii - 1].getNumTimeNodes() - 1), grid[ii].getTimeNode(0)) == 0, "time nodes not consistent");
    }

    final double sMin = xNodes[0];
    final double sMax = xNodes[xNodes.length - 1];
//    ArgumentChecker.isTrue(sMin <= k, "strike lower than sMin");
//    ArgumentChecker.isTrue(sMax >= k, "strike higher than sMax");

    final int index = Arrays.binarySearch(xNodes, r0);
    ArgumentChecker.isTrue(index >= 0, "cannot find spot on grid");

    //final double q = r - b;
    
    //final Function1D<Double, Double> payoff = ICP.getEuropeanPayoff(k, isCall);

    BoundaryCondition lower;
    BoundaryCondition upper;

    PDEFullResults1D res;

        
        final Function1D<Double, Double> upFunc = new Function1D<Double, Double>() {
          @Override
          public Double evaluate(final Double time) {
            return Math.exp(-rMax * time);
          }
        };
        upper = new NeumannBoundaryCondition(upFunc, sMax, false);
      
        final Function1D<Double, Double> downFunc = new Function1D<Double, Double>() {
          @Override
          public Double evaluate(final Double time) {
            return Math.exp(-rMin * time);
          }
        };
        lower = new NeumannBoundaryCondition(downFunc, sMin, true);
        
        
     //final FunctionalDoublesSurface free = new FunctionalDoublesSurface(func);

      PDE1DDataBundle<ConvectionDiffusionPDE1DCoefficients> data = new PDE1DDataBundle<ConvectionDiffusionPDE1DCoefficients>(coef, initial, lower, upper, grid[0]);
      ThetaMethodFiniteDifference solver = new ThetaMethodFiniteDifference(theta[0], true);
      res = (PDEFullResults1D) solver.solve(data);
      
      
      
		/*
		 * for (int ii = 1; ii < n; ii++) { data = new
		 * PDE1DDataBundle<ConvectionDiffusionPDE1DCoefficients>(coef,
		 * res.getTerminalResults(), lower, upper, grid[ii]); solver = new
		 * ThetaMethodFiniteDifference(theta[ii], true); res = (PDEFullResults1D)
		 * solver.solve(data); }
		 */
      
      //System.out.println(SandBox.price((double)(T/2), (double)T, r0));

    return res;
  }
}
