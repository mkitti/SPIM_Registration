package spim.process.interestpointregistration.geometrichashing;

import ij.gui.GenericDialog;

import java.util.List;

import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.TimePoint;
import spim.fiji.plugin.Interest_Point_Registration.RegistrationType;
import spim.fiji.plugin.interestpointregistration.InterestPointRegistration;
import spim.fiji.spimdata.SpimData2;
import spim.process.interestpointregistration.ChannelProcess;
import spim.process.interestpointregistration.PairwiseMatch;
import spim.process.interestpointregistration.RANSACParameters;
import spim.process.interestpointregistration.TransformationModel;

public class GeometricHashing extends InterestPointRegistration
{
	public static int defaultModel = 2;	
	public static boolean defaultRegularize = false;
	protected TransformationModel model = null;

	protected RANSACParameters ransacParams;

	public GeometricHashing(
			final SpimData2 spimData,
			final List< Angle > anglesToProcess,
			final List< ChannelProcess > channelsToProcess,
			final List< Illumination > illumsToProcess,
			final List< TimePoint > timepointsToProcess )
	{
		super( spimData, anglesToProcess, channelsToProcess, illumsToProcess, timepointsToProcess );
	}

	@Override
	protected GeometricHashingPairwise pairwiseMatchingInstance( final PairwiseMatch pair, final String description )
	{
		return new GeometricHashingPairwise( pair, model, description, ransacParams );
	}

	@Override
	protected TransformationModel getTransformationModel() { return model; }

	@Override
	public GeometricHashing newInstance(
			final SpimData2 spimData,
			final List< Angle > anglesToProcess,
			final List< ChannelProcess > channelsToProcess,
			final List< Illumination > illumsToProcess,
			final List< TimePoint > timepointsToProcess )
	{
		return new GeometricHashing( spimData, anglesToProcess, channelsToProcess, illumsToProcess, timepointsToProcess );
	}

	@Override
	public String getDescription() { return "Fast 3d geometric hashing (rotation invariant)";}

	@Override
	public void addQuery( final GenericDialog gd, final RegistrationType registrationType )
	{
		gd.addChoice( "Transformation model", TransformationModel.modelChoice, TransformationModel.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );
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

		final float maxEpsilon = RANSACParameters.max_epsilon = (float)gd.getNextNumber();
		
		this.ransacParams = new RANSACParameters( maxEpsilon, RANSACParameters.min_inlier_ratio, RANSACParameters.min_inlier_factor, RANSACParameters.num_iterations );

		return true;
	}
}