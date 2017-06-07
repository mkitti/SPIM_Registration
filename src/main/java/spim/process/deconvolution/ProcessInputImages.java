package spim.process.deconvolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import spim.fiji.spimdata.SpimData2;
import spim.process.fusion.deconvolution.MVDeconvolution;
import spim.process.fusion.export.DisplayImage;
import spim.process.fusion.transformed.FusedRandomAccessibleInterval;
import spim.process.fusion.transformed.FusedWeightsRandomAccessibleInterval;
import spim.process.fusion.transformed.TransformView;
import spim.process.fusion.transformed.TransformVirtual;
import spim.process.fusion.transformed.TransformWeight;
import spim.process.fusion.weightedavg.ProcessFusion;
import spim.process.fusion.weightedavg.ProcessVirtual;
import spim.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class ProcessInputImages
{
	public static int defaultBlendingRangeNumber = 12;
	public static int defaultBlendingBorderNumber = -8;

	public static < V extends ViewId > void preProcessVirtual(
			final AbstractSpimData< ? extends AbstractSequenceDescription< ? extends BasicViewSetup, ? extends BasicViewDescription< ? >, ? extends BasicImgLoader > > spimData,
			final Collection< Group< V > > groups,
			Interval bb )
	{
		preProcessVirtual( spimData, groups, bb, Double.NaN );
	}

	public static < V extends ViewId > void preProcessVirtual(
			final AbstractSpimData< ? extends AbstractSequenceDescription< ? extends BasicViewSetup, ? extends BasicViewDescription< ? >, ? extends BasicImgLoader > > spimData,
			final Collection< Group< V > > groups,
			Interval bb,
			final double downsampling )
	{
		int i = 0;

		// scale the bounding box if necessary
		if ( !Double.isNaN( downsampling ) )
			bb = TransformVirtual.scaleBoundingBox( bb, 1.0 / downsampling );

		final long[] dim = new long[ bb.numDimensions() ];
		bb.dimensions( dim );

		for ( final Group< V > group : groups )
		{
			SpimData2.filterMissingViews( spimData, group.getViews() );

			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Transforming group " + (++i) + " of " + groups.size() + " (group=" + group + ")" );

			if ( group.getViews().size() == 0 )
			{
				IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Group is empty. Continuing with next one." );
				continue;
			}

			final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
			final ArrayList< RandomAccessibleInterval< FloatType > > weightsFusion = new ArrayList<>();
			final ArrayList< RandomAccessibleInterval< FloatType > > weightsDecon = new ArrayList<>();

			for ( final ViewId viewId : group.getViews() )
			{
				final BasicImgLoader imgloader = spimData.getSequenceDescription().getImgLoader();
				final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
				vr.updateModel();
				AffineTransform3D model = vr.getModel();

				// adjust the model for downsampling
				if ( !Double.isNaN( downsampling ) )
				{
					model = model.copy();
					TransformVirtual.scaleTransform( model, 1.0 / downsampling );
				}

				// we need a different blending when virtually fusing the images since a negative
				// value would actually lead to artifacts there
				final float[] blendingFusion = ProcessFusion.defaultBlendingRange.clone();
				final float[] borderFusion = ProcessFusion.defaultBlendingBorder.clone();

				// however, to then run the deconvolution with this data, we want negative values
				// to maximize the usage of image data
				final float[] blendingDecon = new float[]{ defaultBlendingRangeNumber, defaultBlendingRangeNumber, defaultBlendingRangeNumber };
				final float[] borderDecon = new float[]{ defaultBlendingBorderNumber, defaultBlendingBorderNumber, defaultBlendingBorderNumber };

				// adjust both for z-scaling (anisotropy)
				ProcessVirtual.adjustBlending( spimData.getSequenceDescription().getViewDescriptions().get( viewId ), blendingFusion, borderFusion );
				ProcessVirtual.adjustBlending( spimData.getSequenceDescription().getViewDescriptions().get( viewId ), blendingDecon, borderDecon );

				final RandomAccessibleInterval inputImg = TransformView.openDownsampled( imgloader, viewId, model );

				images.add( TransformView.transformView( inputImg, model, bb, MVDeconvolution.minValueImg, 0, 1 ) );
				weightsFusion.add( TransformWeight.transformBlending( inputImg, borderFusion, blendingFusion, model, bb ) );
				weightsDecon.add( TransformWeight.transformBlending( inputImg, borderDecon, blendingDecon, model, bb ) );
			}

			final RandomAccessibleInterval< FloatType > img = new FusedRandomAccessibleInterval( new FinalInterval( dim ), images, weightsFusion );
			final RandomAccessibleInterval< FloatType > weight = new FusedWeightsRandomAccessibleInterval( new FinalInterval( dim ), weightsDecon );
			
			DisplayImage.getImagePlusInstance( img, true, "image", 0, 255 ).show();
			DisplayImage.getImagePlusInstance( weight, true, "weightsDecon", 0, 1 ).show();

			DisplayImage.getImagePlusInstance( new FusedWeightsRandomAccessibleInterval( new FinalInterval( dim ), weightsFusion ), true, "weightsFusion", 0, 1 ).show();

		}
	}
}
