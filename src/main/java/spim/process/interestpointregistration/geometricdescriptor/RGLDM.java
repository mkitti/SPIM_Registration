/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2007 - 2021 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package spim.process.interestpointregistration.geometricdescriptor;

import ij.gui.GenericDialog;

import java.util.List;

import mpicbg.spim.data.sequence.ViewId;
import spim.fiji.plugin.Interest_Point_Registration.RegistrationType;
import spim.fiji.plugin.interestpointregistration.InterestPointRegistration;
import spim.fiji.spimdata.SpimData2;
import spim.process.interestpointregistration.ChannelProcess;
import spim.process.interestpointregistration.PairwiseMatch;
import spim.process.interestpointregistration.RANSACParameters;
import spim.process.interestpointregistration.TransformationModel;

/**
 * Redundant Geometric Local Descriptor Matching (RGLDM)
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class RGLDM extends InterestPointRegistration
{
	public static int defaultModel = 2;
	public static boolean defaultRegularize = true;
	protected TransformationModel model = null;

	protected RGLDMParameters parameters;
	protected RANSACParameters ransacParams;

	public RGLDM(
			final SpimData2 spimData,
			final List< ViewId > viewIdsToProcess,
			final List< ChannelProcess > channelsToProcess )
	{
		super( spimData, viewIdsToProcess, channelsToProcess );
	}

	@Override
	protected RGLDMPairwise pairwiseMatchingInstance( final PairwiseMatch pair, final String description )
	{
		return new RGLDMPairwise( pair, model, description, ransacParams, parameters );
	}

	@Override
	protected TransformationModel getTransformationModel() { return model; }

	@Override
	public RGLDM newInstance(
			final SpimData2 spimData,
			final List< ViewId > viewIdsToProcess,
			final List< ChannelProcess > channelsToProcess )
	{
		return new RGLDM( spimData, viewIdsToProcess, channelsToProcess );
	}


	@Override
	public String getDescription() { return "Redundant geometric local descriptor matching (translation invariant)";}

	@Override
	public void addQuery( final GenericDialog gd, final RegistrationType registrationType )
	{
		gd.addChoice( "Transformation model", TransformationModel.modelChoice, TransformationModel.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );
		gd.addSlider( "Number_of_neighbors for the descriptors", 3, 10, RGLDMParameters.numNeighbors );
		gd.addSlider( "Redundancy for descriptor matching", 0, 10, RGLDMParameters.redundancy );		
		gd.addSlider( "Significance required for a descriptor match", 1.0, 10.0, RGLDMParameters.ratioOfDistance );
		gd.addSlider( "Allowed_error_for_RANSAC (px)", 0.5, 20.0, RANSACParameters.max_epsilon );
	}

	@Override
	public boolean parseDialog( final GenericDialog gd, final RegistrationType registrationType )
	{
		model = new TransformationModel( defaultModel = gd.getNextChoiceIndex() );
		
		if ( defaultRegularize = gd.getNextBoolean() )
		{
			if ( !model.queryRegularizedModel() )
				return false;
		}

		final int numNeighbors = RGLDMParameters.numNeighbors = (int)Math.round( gd.getNextNumber() );
		final int redundancy = RGLDMParameters.redundancy = (int)Math.round( gd.getNextNumber() );
		final float significance = RGLDMParameters.ratioOfDistance = (float)gd.getNextNumber();
		final float maxEpsilon = RANSACParameters.max_epsilon = (float)gd.getNextNumber();
		
		this.parameters = new RGLDMParameters( RGLDMParameters.differenceThreshold, significance, numNeighbors, redundancy );
		this.ransacParams = new RANSACParameters( maxEpsilon, RANSACParameters.min_inlier_ratio, RANSACParameters.min_inlier_factor, RANSACParameters.num_iterations );
		
		return true;
	}
}
