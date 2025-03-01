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
package mpicbg.spim.mpicbg;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import mpicbg.models.AbstractAffineModel3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;
import mpicbg.spim.registration.bead.Bead;
import mpicbg.spim.registration.segmentation.Nucleus;
import mpicbg.spim.vis3d.VisualizationSketchTikZ;
import mpicbg.util.TransformUtils;
import spim.vecmath.Transform3D;
import spim.vecmath.Vector3d;

public class TileConfigurationSPIM
{
	final static private DecimalFormat decimalFormat = new DecimalFormat();
	final static private DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();

	final private Set< TileSPIM > tiles = new HashSet< TileSPIM >();
	final public Set< TileSPIM > getTiles(){ return tiles; }
	
	final private Set< TileSPIM > fixedTiles = new HashSet< TileSPIM >();
	final public Set< TileSPIM > getFixedTiles(){ return fixedTiles; }
	
	private double minError = Double.MAX_VALUE;
	final public double getMinError() {	return minError; }
	
	private double maxError = 0.0;
	final public double getMaxError() { return maxError; }
	
	private double error = Double.MAX_VALUE;
	final public double getError() { return error; }

	protected int debugLevel;
	
	public TileConfigurationSPIM( final int debugLevel )
	{
		this.debugLevel = debugLevel;
		
		decimalFormatSymbols.setGroupingSeparator( ',' );
		decimalFormatSymbols.setDecimalSeparator( '.' );
		decimalFormat.setDecimalFormatSymbols( decimalFormatSymbols );
		decimalFormat.setMaximumFractionDigits( 3 );
		decimalFormat.setMinimumFractionDigits( 3 );		
	}
	
	protected void println( String s )
	{
		if ( debugLevel <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println( s ); 
	}
	
	/**
	 * Cleanup.
	 */
	public void clear()
	{
		tiles.clear();
		fixedTiles.clear();
		
		minError = Double.MAX_VALUE;
		maxError = 0.0;
		error = Double.MAX_VALUE;
	}
	
	/**
	 * Add a single {@link Tile}.
	 * 
	 * @param t
	 */
	final public void addTile( final TileSPIM t ){ tiles.add( t ); }
	
	/**
	 * Add a {@link Collection} of {@link Tile Tiles}.
	 * 
	 * @param t
	 */
	final public void addTiles( final Collection< ? extends TileSPIM > t ){ tiles.addAll( t ); }
	
	/**
	 * Add all {@link Tile Tiles} of another {@link TileConfiguration}.
	 * 
	 * @param t
	 */
	final public void addTiles( final TileConfigurationSPIM t ){ tiles.addAll( t.tiles ); }
	
	/**
	 * Fix a single {@link Tile}.
	 * 
	 * @param t
	 */
	final public void fixTile( final TileSPIM t ){ fixedTiles.add( t ); }
	
	/**
	 * Update all {@link PointMatch Correspondences} in all {@link Tile Tiles}
	 * and estimate the average displacement. 
	 */
	final protected void update()
	{
		double cd = 0.0;
		minError = Double.MAX_VALUE;
		maxError = 0.0;
		for ( TileSPIM t : tiles )
		{
			t.update();
			double d = t.getDistance();
			if ( d < minError ) minError = d;
			if ( d > maxError ) maxError = d;
			cd += d;
		}
		cd /= tiles.size();
		error = cd;
	}
	
	final public void computeError()
	{
		update();
		
		if ( debugLevel <= ViewStructure.DEBUG_MAIN )
		{
			println( "  average displacement: " + decimalFormat.format( error ) + "px" );
			println( "  minimal displacement: " + decimalFormat.format( minError ) + "px" );
			println( "  maximal displacement: " + decimalFormat.format( maxError ) + "px" );
		}
	}
	
	/**
	 * Minimize the displacement of all {@link PointMatch Correspondence pairs}
	 * of all {@link Tile Tiles}
	 * 
	 * @param maxAllowedError do not accept convergence if error is &lt; max_error
	 * @param maxIterations stop after that many iterations even if there was
	 *   no minimum found
	 * @param maxPlateauwidth convergence is reached if the average absolute
	 *   slope in an interval of this size and half this size is smaller than
	 *   0.0001 (in double accuracy).  This is assumed to prevent the algorithm
	 *   from stopping at plateaus smaller than this value.
	 * @param debugLevel defines if the Optimizer prints the output at the end of the process
	 */
	public void optimize(
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth,
			final int debugLevel ) throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{
		final ErrorStatistic observer = new ErrorStatistic( maxPlateauwidth + 1 );
		
		int i = 0;
		
		boolean proceed = i < maxIterations;
		
		while ( proceed )
		{
			for ( final TileSPIM tile : tiles )
			{
				if ( fixedTiles.contains( tile ) ) continue;
				tile.update();
				tile.fitModel();
				tile.update();
			}
			update();
			observer.add( error );
			
			if ( i > maxPlateauwidth )
			{
				proceed = error > maxAllowedError;
				
				int d = maxPlateauwidth;
				while ( !proceed && d >= 1 )
				{
					try
					{
						proceed |= Math.abs( observer.getWideSlope( d ) ) > 0.0001;
					}
					catch ( Exception e ) { e.printStackTrace(); }
					d /= 2;
				}
			}
			
			proceed &= ++i < maxIterations;
		}
		
		if ( debugLevel <= ViewStructure.DEBUG_MAIN )
		{
			println( "Successfully optimized configuration of " + tiles.size() + " tiles after " + i + " iterations:" );
			println( "  average displacement: " + decimalFormat.format( error ) + "px" );
			println( "  minimal displacement: " + decimalFormat.format( minError ) + "px" );
			println( "  maximal displacement: " + decimalFormat.format( maxError ) + "px" );
		}
	}
	
	/**
	 * Minimize the displacement of all {@link PointMatch Correspondence pairs}
	 * of all {@link Tile Tiles}
	 * 
	 * @param maxAllowedError do not accept convergence if error is &lt; max_error
	 * @param maxIterations stop after that many iterations even if there was
	 *   no minimum found
	 * @param maxPlateauwidth convergence is reached if the average absolute
	 *   slope in an interval of this size and half this size is smaller than
	 *   0.0001 (in double accuracy).  This is assumed to prevent the algorithm
	 *   from stopping at plateaus smaller than this value.
	 * @param debugLevel defines if the Optimizer prints the output at the end of the process
	 */
	public void optimizeWithSketchTikZ(
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth,
			final int debugLevel ) throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{
		final ErrorStatistic observer = new ErrorStatistic( maxPlateauwidth + 1 );
		
		int i = 0;
		final double factor = 0.005;
		
		boolean proceed = i < maxIterations;
		
		while ( proceed )
		{
			for ( final TileSPIM tile : tiles )
			{
				if ( fixedTiles.contains( tile ) ) continue;
				tile.updateWithDections();
				if ( i > 0 )
				{
					tile.fitModel();
					tile.updateWithDections();
				}
			}
			
			//if ( i == 0 )
			//if ( error < 7.5 )
			{
				SketchTikZFileObject files = SketchTikZFileObject.initOutputFile( "src/templates/beadimage-movie.sk", "src/templates/movie/dros_nucleibased_" + i + ".sk" );
				
				for ( TileSPIM tile : tiles )
				{
					final AbstractAffineModel3D<?> m = (AbstractAffineModel3D<?>)tile.getModel().copy();
					
					Transform3D t = TransformUtils.getTransform3D1( m ); 
					ViewDataBeads parent = tile.getParent();
													
					// the bounding box is not scaled yet, so we have to apply
					// the correct z stretching
					Transform3D tmp = new Transform3D();
					Transform3D tmp2 = new Transform3D(t);
					tmp.setScale( new Vector3d(1, 1, parent.getZStretching()) );							
					tmp2.mul( tmp );				
					t = tmp2;
					
					// back up the model
					final AbstractAffineModel3D backUp = (AbstractAffineModel3D)tile.getModel().copy();
					backUp.set( (AbstractAffineModel3D)parent.getTile().getModel() );
					
					parent.getTile().getModel().set( TransformUtils.getAffineModel3D( t ) );
					
					/*
					Transform3D backUpT3D = parent.transformation;
					Transform3D backUpIT3D = parent.inverseTransformation;
					parent.transformation = t;
					parent.inverseTransformation = new Transform3D( t );
					parent.inverseTransformation.invert();
					*/
					
					System.out.println("Writing view " + parent.getName() + " @ iteration " + i );
					files.getOutput().println( VisualizationSketchTikZ.drawView( parent, factor ) );
					files.getOutput().println( VisualizationSketchTikZ.drawBeads( parent.getBeadStructure().getBeadList(), TransformUtils.getTransform3D1( m ), "Bead", factor, 2 ) );
					
					for ( Bead bead : parent.getBeadStructure().getBeadList() )
					{
						double distance = bead.getDistance();
						if ( distance >= 0 )
						{
							int color = (int)Math.round( Math.log10( distance + 1 ) * 256.0 );
							
							// max value == 100
							if ( color > 511 )
								color = 511;
							
							if ( color < 0)
								color = 0;
							
							files.getOutput().println( VisualizationSketchTikZ.drawBead( bead, TransformUtils.getTransform3D1( m ), "RansacBead" + color, factor ) );
						}
					}
				
					// write back old (unscaled) model
					
					parent.getTile().getModel().set( backUp );
					
					//parent.transformation = backUpT3D;
					//parent.inverseTransformation = backUpIT3D;
				}
				
				files.finishFiles();
				
				//System.exit( 0 );
			}
			
			update();
			observer.add( error );
			
			if ( i > maxPlateauwidth )
			{
				proceed = error > maxAllowedError;
				
				int d = maxPlateauwidth;
				while ( !proceed && d >= 1 )
				{
					try
					{
						proceed |= Math.abs( observer.getWideSlope( d ) ) > 0.0001;
					}
					catch ( Exception e ) { e.printStackTrace(); }
					d /= 2;
				}
			}
			
			proceed &= ++i < maxIterations;
		}
		
		if ( debugLevel <= ViewStructure.DEBUG_MAIN )
		{
			println( "Successfully optimized configuration of " + tiles.size() + " tiles after " + i + " iterations:" );
			println( "  average displacement: " + decimalFormat.format( error ) + "px" );
			println( "  minimal displacement: " + decimalFormat.format( minError ) + "px" );
			println( "  maximal displacement: " + decimalFormat.format( maxError ) + "px" );
		}
	}

	/**
	 * Minimize the displacement of all {@link PointMatch Correspondence pairs}
	 * of all {@link Tile Tiles}
	 * 
	 * @param maxAllowedError do not accept convergence if error is > max_error
	 * @param maxIterations stop after that many iterations even if there was
	 *   no minimum found
	 * @param maxPlateauwidth convergence is reached if the average absolute
	 *   slope in an interval of this size and half this size is smaller than
	 *   0.0001 (in double accuracy).  This is assumed to prevent the algorithm
	 *   from stopping at plateaus smaller than this value.
	 * @param debugLevel defines if the Optimizer prints the output at the end of the process
	 */
	/**
	 * Minimize the displacement of all {@link PointMatch Correspondence pairs}
	 * of all {@link Tile Tiles}
	 * 
	 * @param maxAllowedError do not accept convergence if error is &gt; max_error
	 * @param maxIterations stop after that many iterations even if there was
	 *   no minimum found
	 * @param maxPlateauwidth convergence is reached if the average absolute
	 *   slope in an interval of this size and half this size is smaller than
	 *   0.0001 (in double accuracy).  This is assumed to prevent the algorithm
	 *   from stopping at plateaus smaller than this value.
	 * @param debugLevel defines if the Optimizer prints the output at the end of the process
	 */
	public void optimizeWithSketchTikZNuclei(
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth,
			final int debugLevel ) throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{
		final ErrorStatistic observer = new ErrorStatistic( maxPlateauwidth + 1 );
		
		//
		// parameters for movie
		//
		
		// how many detailed iterations to show (one frame per updated angle)
		final int numDetailedIterations = 5;
		// till which error show one frame per iteration 
		final double thresholdErrorFastMovie = 7.5;
		// from the error threshold on, every how many iterations to do one frame
		final int ratioFastMovie = 8;
		
		// in a first run find minX, minY, minZ
		// for that we need a constant order of tiles
		final ArrayList<ViewDataBeads> tilesSorted = new ArrayList<ViewDataBeads>();
		for ( final TileSPIM<?> tile : tiles )
			tilesSorted.add( tile.getParent() );		
		Collections.sort( tilesSorted );
		
		int i = 0, j = 0, frame = 0;
		final double factor = 0.01 * 0.45;
		
		int freeTiles = 0;
		for ( final Tile<?> tile : tiles )
			if ( !fixedTiles.contains( tile ) )
				freeTiles++;
		
		boolean proceed = i < maxIterations;
		
		while ( proceed )
		{						
			//for ( final TileSPIM<?> tile1 : tiles )
			for ( final ViewDataBeads view : tilesSorted )
			{
				final TileSPIM<?> tile1 = view.getTile();
				
				if ( fixedTiles.contains( tile1 ) ) continue;
				tile1.updateWithDections();
				if ( i > 0 )
				{
					tile1.fitModel();
					tile1.updateWithDections();
				}
				
				// to get the most accurate error
				update();

				if ( j >= freeTiles-1 && ( j <= (numDetailedIterations+1)*freeTiles || 
						                 ( error > thresholdErrorFastMovie && j%freeTiles == 0 ) || 
						                 ( error <= thresholdErrorFastMovie && j%(freeTiles*ratioFastMovie) == 0 )) )
				{
					System.out.println( j + "(" + i + ") " + error + " frame=" + frame );
					
					//if ( frame < 10 || frame == 525 )
					{
						SketchTikZFileObject files = SketchTikZFileObject.initOutputFile( "src/templates/fish-movie.sk", "src/templates/movie_fish/fish_" + frame + ".sk" );
						for ( TileSPIM tile : tiles )
						{
							final AffineModel3D m = new AffineModel3D();
							m.set( (AffineModel3D)tile.getModel() );
							
							Transform3D t = TransformUtils.getTransform3D( m ); 
							ViewDataBeads parent = tile.getParent();
															
							// the bounding box is not scaled yet, so we have to apply
							// the correct z stretching
							Transform3D tmp = new Transform3D();
							Transform3D tmp2 = new Transform3D(t);
							tmp.setScale( new Vector3d(1, 1, parent.getZStretching()) );							
							tmp2.mul( tmp );				
							t = tmp2;
							
							// back up the model
							AffineModel3D backUp = new AffineModel3D( );
							backUp.set( (AffineModel3D)parent.getTile().getModel() );
							
							parent.getTile().getModel().set( TransformUtils.getAffineModel3D( t ) );
							
							/*
							Transform3D backUpT3D = parent.transformation;
							Transform3D backUpIT3D = parent.inverseTransformation;
							parent.transformation = t;
							parent.inverseTransformation = new Transform3D( t );
							parent.inverseTransformation.invert();
							*/
							
							//System.out.println("Writing view " + parent.getName() + " @ iteration " + i );
							files.getOutput().println( VisualizationSketchTikZ.drawView( parent, factor ) );
							files.getOutput().println( VisualizationSketchTikZ.drawNuclei( parent.getNucleiStructure().getNucleiList(), TransformUtils.getTransform3D( m ), factor ) );
							
							for ( Nucleus nucleus : parent.getNucleiStructure().getNucleiList() )
							{
								double distance = nucleus.getDistance();
								if ( distance >= 0 )
								{
									int color = (int)Math.round( Math.log10( distance + 1 ) * 256.0 );
									
									// max value == 100
									if ( color > 511 )
										color = 511;
									
									if ( color < 0)
										color = 0;
									
									files.getOutput().println( VisualizationSketchTikZ.drawNucleus( nucleus, TransformUtils.getTransform3D( m ), "RansacBead" + color, factor ) );
								}
							}
						
							// write back old (unscaled) model
							
							parent.getTile().getModel().set( backUp );
							
							//parent.transformation = backUpT3D;
							//parent.inverseTransformation = backUpIT3D;
						}
						
						files.putTextEntry( 14.0f, -4.5f, "Iteration " + i + " (" + decimalFormat.format( error ) + " px)" );
						files.finishFiles();
					}
					frame++;
					//System.exit( 0 );
				}
				++j;
			}
						
			update();
			observer.add( error );
			
			if ( i > maxPlateauwidth )
			{
				proceed = error > maxAllowedError;
				
				int d = maxPlateauwidth;
				while ( !proceed && d >= 1 )
				{
					try
					{
						proceed |= Math.abs( observer.getWideSlope( d ) ) > 0.0001;
					}
					catch ( Exception e ) { e.printStackTrace(); }
					d /= 2;
				}
			}
			
			proceed &= ++i < maxIterations;
		}
		
		if ( debugLevel <= ViewStructure.DEBUG_MAIN )
		{
			println( "Successfully optimized configuration of " + tiles.size() + " tiles after " + i + " iterations:" );
			println( "  average displacement: " + decimalFormat.format( error ) + "px" );
			println( "  minimal displacement: " + decimalFormat.format( minError ) + "px" );
			println( "  maximal displacement: " + decimalFormat.format( maxError ) + "px" );
		}
	}
	
	/*
	public void optimizeWithSketchTikZ(
			double maxAllowedError,
			int maxIterations,
			int maxPlateauwidth,
			ViewDataBeads[] views,
			boolean showDetails ) throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{
		final double factor = 0.005;
		ErrorStatistic observer = new ErrorStatistic( maxPlateauwidth + 1 );
		
		int i = 0;
		
		while ( i < maxIterations )  // do not run forever
		{
			for ( TileSPIM tile : tiles )
			{
				if ( !fixedTiles.contains( tile ) )
				{
					tile.updateWithBeads();
					if ( i > 0 )
					{
						tile.fitModel();
						tile.updateWithBeads();
					}
				}
			}
			
			if ( i == 10 )
			{
				FileObject files = FileObject.initOutputFile( "src/templates/beadimage-movie.sk", "src/templates/movie/beadimage_" + i + ".sk" );
				
				for ( TileSPIM tile : tiles )
				{
					AffineModel3D m = (AffineModel3D) tile.getModel();
					Transform3D t = MathLib.getTransform3D( m ); 
					ViewDataBeads parent = tile.getParent();
													
					// the bounding box is not scaled yet, so we have to apply
					// the correct z stretching
					Transform3D tmp = new Transform3D();
					Transform3D tmp2 = new Transform3D(t);
					tmp.setScale( new Vector3d(1, 1, parent.getZStretching()) );							
					tmp2.mul( tmp );				
					t = tmp2;
					
					// back up the model
					AffineModel3D backUp = new AffineModel3D( );
					backUp.set( parent.getTile().getModel() );
					
					parent.getTile().getModel().set( MathLib.getAffineModel3D( t ) );
					
					System.out.println("Writing view " + parent.getName() + " @ iteration " + i );
					files.getOutput().println( VisualizationSketchTikZ.drawView( parent, factor ) );
					files.getOutput().println( VisualizationSketchTikZ.drawBeads( parent.getBeadStructure().getBeadList(), MathLib.getTransform3D( m ), "Bead", factor ) );
					
					for ( Bead bead : parent.getBeadStructure().getBeadList() )
					{
						double distance = bead.getDistance();
						if ( distance >= 0 )
						{
							int color = Math.round( (double)Math.log10( distance + 1 ) * 256f );
							
							// max value == 100
							if ( color > 511 )
								color = 511;
							
							if ( color < 0)
								color = 0;
							
							files.getOutput().println( VisualizationSketchTikZ.drawBead( bead, MathLib.getTransform3D( m ), "RansacBead" + color, factor ) );
						}
					}
				
					// write back old (unscaled) model
					
					parent.getTile().getModel().set( backUp );
				}
				
				files.finishFiles();
				
				System.exit( 0 );
			}
			
			update();
			observer.add( error );			
			
			if (
					i >= maxPlateauwidth &&
					error < maxAllowedError &&
					Math.abs( observer.getWideSlope( maxPlateauwidth ) ) <= 0.0001 &&
					Math.abs( observer.getWideSlope( maxPlateauwidth / 2 ) ) <= 0.0001 )
			{
				break;
			}
			++i;
		}
		
		if (showDetails)
		{
			System.out.println( "Successfully optimized configuration of " + tiles.size() + " tiles after " + i + " iterations:" );
			System.out.println( "  average displacement: " + decimalFormat.format( error ) + "px" );
			System.out.println( "  minimal displacement: " + decimalFormat.format( minError ) + "px" );
			System.out.println( "  maximal displacement: " + decimalFormat.format( maxError ) + "px" );
		}
	}
	*/
	

	/**
	 * Computes a pre-alignemnt of all non-fixed {@link Tile}s by propagating the pairwise
	 * models. This does not give a correct registration but a very good starting point
	 * for the global optimization. This is necessary for models where the global optimization
	 * is not guaranteed to converge like the {@link HomographyModel2D}, {@link RigidModel3D}, ... 
	 * 
	 * @return - a list of {@link Tile}s that could not be pre-aligned
	 * @throws NotEnoughDataPointsException
//	 * @throws {@link IllDefinedDataPointsException}
	 */
	public List< Tile< ? > > preAlign() throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{	
		// first get order all tiles by
		// a) unaligned
		// b) aligned - which initially only contains the fixed ones
		final ArrayList< Tile< ? > > unAlignedTiles = new ArrayList< Tile< ? > >();
		final ArrayList< Tile< ? > > alignedTiles = new ArrayList< Tile< ? > >();

		// if no tile is fixed, take another */
		if ( getFixedTiles().size() == 0 )
		{
			final Iterator< ? extends Tile > it = this.getTiles().iterator();
			alignedTiles.add( it.next() );
			while ( it.hasNext() )
				unAlignedTiles.add( it.next() );
		}
		else
		{
			for ( final Tile< ? > tile : this.getTiles() )
			{
				if ( this.getFixedTiles().contains( tile ) )
					alignedTiles.add( tile );
				else
					unAlignedTiles.add( tile );
			}
		}
		
		// we go through each fixed/aligned tile and try to find a pre-alignment
		// for all other unaligned tiles
		for ( final ListIterator< Tile< ?> > referenceIterator = alignedTiles.listIterator(); referenceIterator.hasNext(); )
		{
			// once all tiles are aligned we can quit this loop
			if ( unAlignedTiles.size() == 0 )
				break;
			
			// get the next reference tile (either a fixed or an already aligned one
			final Tile< ? > referenceTile = referenceIterator.next();

			// transform all reference points into the reference coordinate system
			// so that we get the direct model even if we are not anymore at the
			// level of the fixed tile
			referenceTile.apply();
			
			// now we go through the unaligned tiles to see if we can align it to the current reference tile one
			for ( final ListIterator< Tile< ?> > targetIterator = unAlignedTiles.listIterator(); targetIterator.hasNext(); )
			{
				// get the tile that we want to preregister
				final Tile< ? > targetTile = targetIterator.next();

				// target tile is connected to reference tile
				if ( referenceTile.getConnectedTiles().contains( targetTile ) )
				{
					// extract all PointMatches between reference and target tile and fit a model only on these
					final ArrayList< PointMatch > pm = getConnectingPointMatches( targetTile, referenceTile );
					
					// are there enough matches?
					if ( pm.size() > targetTile.getModel().getMinNumMatches() )
					{
						// fit the model of the targetTile to the subset of matches
						// mapping its local coordinates target.p.l into the world
						// coordinates reference.p.w
						// this will give us an approximation for the global optimization
						targetTile.getModel().fit( pm );							
						
						// now that we managed to fit the model we remove the
						// Tile from unaligned tiles and add it to aligned tiles
						targetIterator.remove();
						
						// now add the aligned target tile to the end of the reference list
						int countFwd = 0;
						
						while ( referenceIterator.hasNext() )
						{
							referenceIterator.next();
							++countFwd;
						}
						referenceIterator.add( targetTile );
						
						// move back to the current position 
						// (+1 because it add just behind the current position)
						for ( int j = 0; j < countFwd + 1; ++j )
							referenceIterator.previous();
					}
				}
				
			}
		}
		
		return unAlignedTiles;
	}

	/**
	 * Returns an {@link ArrayList} of {@link PointMatch} that connect the targetTile and the referenceTile. The order of the
	 * {@link PointMatch} is PointMatch.p1 = target, PointMatch.p2 = reference. A {@link Model}.fit() will then solve the fit
	 * so that target.p1.l is mapped to reference.p2.w.
	 * 
	 * @param targetTile - the {@link Tile} for which a {@link Model} can fit 
	 * @param referenceTile - the {@link Tile} to which target will map
	 * 
	 * @return - an {@link ArrayList} of all {@link PointMatch} that target and reference share
	 */
	public ArrayList<PointMatch> getConnectingPointMatches( final Tile<?> targetTile, final Tile<?> referenceTile ) 
	{
		final Set< PointMatch > referenceMatches = referenceTile.getMatches();
		final ArrayList< Point > referencePoints = new ArrayList<Point>( referenceMatches.size() );
		
		// add all points from the reference tile so that we can search for them
		for ( final PointMatch pm : referenceMatches )
			referencePoints.add( pm.getP1() );
		
		// the result arraylist containing only the pointmatches from the target file
		final ArrayList< PointMatch > connectedPointMatches = new ArrayList<PointMatch>();

		// look for all PointMatches where targetTile.PointMatch.Point2 == referenceTile.PointMatch.Point1
		// i.e. a PointMatch of the target tile that links a Point which is part of reference tile
		for ( final PointMatch pm : targetTile.getMatches() )
			if ( referencePoints.contains( pm.getP2() ) )
				connectedPointMatches.add( pm );
		
		return connectedPointMatches;
	}

}
