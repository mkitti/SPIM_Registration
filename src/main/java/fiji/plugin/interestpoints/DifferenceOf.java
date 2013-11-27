package fiji.plugin.interestpoints;

import ij.IJ;
import ij.gui.GenericDialog;

import java.util.ArrayList;
import java.util.Date;

import javax.vecmath.Point3d;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.algorithm.scalespace.SubpixelLocalization;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussian.SpecialPoint;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.registration.ViewStructure;
import mpicbg.spim.registration.bead.Bead;
import mpicbg.spim.registration.bead.SegmentationBenchmark;
import mpicbg.spim.segmentation.SimplePeak;
import fiji.spimdata.SpimDataBeads;

public abstract class DifferenceOf implements InterestPointDetection
{
	/**
	 * Can be used to manually set min (minmaxset[0]) and max (minmaxset[1]) value for all views
	 * that are opened. Otherwise min and max will be read from the images 
	 */
	public static float[] minmaxset = null;

	public static String[] localizationChoice = { "None", "3-dimensional quadratic fit", "Gaussian mask localization fit" };	
	public static String[] brightnessChoice = { "Very weak & small (beads)", "Weak & small (beads)", "Comparable to Sample & small (beads)", "Strong & small (beads)", "Advanced ...", "Interactive ..." };
	
	public static int defaultLocalization = 1;
	public static int[] defaultBrightness = null;
	
	public static int defaultTimepointChoice = 0;
	public static int defaultAngleChoice = 0;
	public static int defaultIlluminationChoice = 0;
	
	public SegmentationBenchmark benchmark = new SegmentationBenchmark();

	/**
	 * which channels to process, set in queryParameters
	 */
	protected ArrayList< Channel> channelsToProcess;
	
	/**
	 * which timepoints to process, set in queryParameters
	 */
	protected ArrayList< TimePoint > timepointsToProcess;
	
	protected int localization;
	protected SpimDataBeads spimData;
	
	@Override
	public boolean queryParameters( final SpimDataBeads spimData, final ArrayList< Channel> channelsToProcess, final ArrayList< TimePoint > timepointsToProcess )
	{
		this.spimData = spimData;
		this.timepointsToProcess = timepointsToProcess;
		this.channelsToProcess = channelsToProcess;
		
		final ArrayList< Channel > channels = spimData.getSequenceDescription().getAllChannels();

		// tell the implementing classes the total number of channels
		init( channels.size() );
		
		final GenericDialog gd = new GenericDialog( getDescription() );
		gd.addChoice( "Subpixel_localization", localizationChoice, localizationChoice[ defaultLocalization ] );
		
		// there are as many channel presets as there are total channels
		if ( defaultBrightness == null || defaultBrightness.length != channels.size() )
		{
			defaultBrightness = new int[ channels.size() ];
			for ( int i = 0; i < channels.size(); ++i )
				defaultBrightness[ i ] = 1;
		}
		
		for ( int c = 0; c < channelsToProcess.size(); ++c )
			gd.addChoice( "Interest_point_specification_(channel_" + channelsToProcess.get( c ).getName() + ")", brightnessChoice, brightnessChoice[ defaultBrightness[ channelsToProcess.get( c ).getId() ] ] );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;
		
		this.localization = defaultLocalization = gd.getNextChoiceIndex();

		for ( int c = 0; c < channelsToProcess.size(); ++c )
		{
			final Channel channel = channelsToProcess.get( c );
			final int brightness = defaultBrightness[ channel.getId() ] = gd.getNextChoiceIndex();
			
			if ( brightness <= 3 )
			{
				if ( !setDefaultValues( channel, brightness ) )
					return false;
			}
			else if ( brightness == 4 )
			{
				if ( !setAdvancedValues( channel ) )
					return false;
			}
			else
			{
				if ( !setInteractiveValues( channel ) )
					return false;
			}
		}
		
		return true;
	}

	protected ArrayList< Point > noLocalization( final ArrayList< SimplePeak > peaks )
	{
		final int n = peaks.get( 0 ).location.length;
		final ArrayList< Point > peaks2 = new ArrayList< Point >();
		
        for ( final SimplePeak peak : peaks )
        {
        	final float[] pos = new float[ n ];
        	
        	for ( int d = 0; d < n; ++d )
        		pos[ d ] = peak.location[ d ];
        	
    		peaks2.add( new Point( pos ) );
        }
        
        return peaks2;		
	}

	protected ArrayList< Point > computeQuadraticLocalization( final ArrayList< SimplePeak > peaks, final Image< FloatType > domImg )
	{
		IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Subpixel localization using quadratic n-dimensional fit");					

        final ArrayList< DifferenceOfGaussianPeak<FloatType> > peakList = new ArrayList<DifferenceOfGaussianPeak<FloatType>>();

        for ( final SimplePeak peak : peaks )
        	peakList.add( new DifferenceOfGaussianPeak<FloatType>( peak.location, new FloatType( peak.intensity ), SpecialPoint.MAX ) );
		
        final SubpixelLocalization<FloatType> spl = new SubpixelLocalization<FloatType>( domImg, peakList );
		spl.setAllowMaximaTolerance( true );
		spl.setMaxNumMoves( 10 );
		
		if ( !spl.checkInput() || !spl.process() )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Warning! Failed to compute subpixel localization " + spl.getErrorMessage() );
		
		final float[] pos = new float[ domImg.getNumDimensions() ];
		
		final ArrayList< Point > peaks2 = new ArrayList< Point >();
		
        for ( DifferenceOfGaussianPeak<FloatType> detection : peakList )
        {
    		detection.getSubPixelPosition( pos );
    		peaks2.add( new Point( pos.clone() ) );
        }
        
        return peaks2;
	}

	protected ArrayList< Point > computeGaussLocalization( final ArrayList< SimplePeak > peaks, final Image< FloatType > domImg, final double sigma )
	{
		// TODO: implement gauss fit
		throw new RuntimeException( "Gauss fit not implemented yet" );
	}
	/**
	 * Figure out which view to use for the interactive preview
	 * 
	 * @param dialogHeader
	 * @param text
	 * @param channel
	 * @return
	 */
	protected ViewId getViewSelection( final String dialogHeader, final String text, final Channel channel )
	{
		final GenericDialog gd = new GenericDialog( dialogHeader );
		
		final String[] timepointNames = new String[ timepointsToProcess.size() ];
		for ( int i = 0; i < timepointNames.length; ++i )
			timepointNames[ i ] = timepointsToProcess.get( i ).getName();
		
		final ArrayList< Angle > angles = spimData.getSequenceDescription().getAllAngles();
		final String[] angleNames = new String[ angles.size() ];
		for ( int i = 0; i < angles.size(); ++i )
			angleNames[ i ] = angles.get( i ).getName();
		
		final ArrayList< Illumination > illuminations = spimData.getSequenceDescription().getAllIlluminations();
		final String[] illuminationNames = new String[ illuminations.size() ];
		for ( int i = 0; i < illuminations.size(); ++i )
			illuminationNames[ i ] = illuminations.get( i ).getName();
		
		gd.addMessage( text );
		gd.addChoice( "Timepoint", timepointNames, timepointNames[ defaultTimepointChoice ] );
		gd.addChoice( "Angle", angleNames, angleNames[ defaultAngleChoice ] );
		gd.addChoice( "Illumination", illuminationNames, illuminationNames[ defaultIlluminationChoice ] );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;
		
		final TimePoint tp = timepointsToProcess.get( defaultTimepointChoice = gd.getNextChoiceIndex() );
		final Angle angle = angles.get( defaultAngleChoice = gd.getNextChoiceIndex() );
		final Illumination illumination = illuminations.get( defaultIlluminationChoice = gd.getNextChoiceIndex() );
		
		final ViewId viewId = getViewId( spimData.getSequenceDescription(), tp, channel, angle, illumination );

		if ( viewId == null )
			IOFunctions.println( "An error occured. Count not find the corresponding ViewSetup for angle: " + angle.getId() + " channel: " + channel.getId() + " illum: " + illumination.getId() );
		
		return viewId;
	}
	
	/**
	 * @param seqDesc
	 * @param t
	 * @param c
	 * @param a
	 * @param i
	 * @return - the ViewId that fits to timepoint, angle, channel & illumination by ID (or null if it does not exist)
	 */
	public static ViewId getViewId( final SequenceDescription<?, ?> seqDesc, final TimePoint t, final Channel c, final Angle a, final Illumination i )
	{
		for ( ViewSetup viewSetup : seqDesc.getViewSetups() )
		{
			if ( viewSetup.getAngle().getId() == a.getId() && 
				 viewSetup.getChannel().getId() == c.getId() && 
				 viewSetup.getIllumination().getId() == i.getId() )
			{
				return new ViewId( t.getId(), viewSetup.getId() );
			}
		}
		
		return null;
	}
	
	/**
	 * This is only necessary to make static objects so that the ImageJ dialog remembers choices
	 * for the right channel
	 * 
	 * @param numChannels - the TOTAL number of channels (not only the ones to process)
	 */
	protected abstract void init( final int numChannels );
	
	protected abstract boolean setDefaultValues( final Channel channel, final int brightness );
	protected abstract boolean setAdvancedValues( final Channel channel );
	protected abstract boolean setInteractiveValues( final Channel channel );
}