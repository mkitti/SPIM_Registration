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
package mpicbg.spim.fusion;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import spim.vecmath.Point3d;

import mpicbg.imglib.cursor.LocalizableByDimCursor;
import mpicbg.imglib.cursor.LocalizableCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.interpolation.Interpolator;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.imglib.util.Util;
import mpicbg.models.AbstractAffineModel3D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.postprocessing.deconvolution.ExtractPSF;
import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;
import fiji.plugin.Multi_View_Deconvolution;

public class PreDeconvolutionFusion extends SPIMImageFusion implements PreDeconvolutionFusionInterface
{
	final Image<FloatType> images[], weights[], overlap;
	final int numViews;
	final boolean normalize;
	final ExtractPSF extractPSF;
	
	public PreDeconvolutionFusion( final ViewStructure viewStructure, final ViewStructure referenceViewStructure, 
								  final ArrayList<IsolatedPixelWeightenerFactory<?>> isolatedWeightenerFactories, 
								  final ArrayList<CombinedPixelWeightenerFactory<?>> combinedWeightenerFactories )
	{
		super( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories );				
		
		// normalize the weights so the the sum for each pixel over all views is 1?
		this.normalize = true;

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Reserving memory for fused image.");
		
		final ImageFactory<FloatType> imageFactory = new ImageFactory<FloatType>( new FloatType(), conf.processImageFactory );
		numViews = viewStructure.getNumViews();
				
		if ( conf.deconvolutionJustShowOverlap )
		{
			overlap = imageFactory.createImage( new int[]{ imgW, imgH, imgD }, "overlap" );
			images = null;
			weights = null;
			extractPSF = null;
		}
		else
		{
			overlap = null;
			
			if ( conf.extractPSF )
				extractPSF = new ExtractPSF( viewStructure );
			else
				extractPSF = ExtractPSF.loadAndTransformPSF( conf.psfFiles, conf.transformPSFs, viewStructure );			
			
			images = new Image[ numViews ];
			weights = new Image[ numViews ];
	
			if ( extractPSF == null )
				return;
	
			for ( int view = 0; view < numViews; view++ )
			{
				weights[ view ] = imageFactory.createImage( new int[]{ imgW, imgH, imgD }, "weights_" + view );
				images[ view ] = imageFactory.createImage( new int[]{ imgW, imgH, imgD }, "view_" + view ); 
				
				if ( images[ view ] == null || weights[ view ] == null )
				{
					if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_ERRORONLY )
						IOFunctions.println("PreDeconvolutionFusion.constructor: Cannot create output image: " + conf.processImageFactory.getErrorMessage() );
	
					return;
				}
			}	
		}
	}
	
	public static void subtractBackground( final Image< FloatType > img, final float value )
	{
		for ( final FloatType t : img )
			t.set( Math.max( 0, t.get() - value ) );
	}

	@Override
	public void fuseSPIMImages( final int channelIndex )
	{
		//
		// update views so that only the current channel is being fused
		//
		final ArrayList<ViewDataBeads> views = new ArrayList<ViewDataBeads>();
		
		for ( final ViewDataBeads view : viewStructure.getViews() )
			if ( view.getChannelIndex() == channelIndex )
				views.add( view );
		
		final int numViews = views.size();

		if ( conf.deconvolutionJustShowOverlap )
		{
			PreDeconvolutionFusionSequential.computeOverlap( overlap, views, viewStructure,cropOffsetX, cropOffsetY, cropOffsetZ, scale, min );
			return;
		}

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("Loading source images (Channel " + channelIndex +  ").");
				
		// this is only single channel for noew
		if ( channelIndex > 0 )
			return;
		
		// load images 
		for ( final ViewDataBeads view : views )
		{
			view.getImage();
			if ( Multi_View_Deconvolution.subtractBackground != 0 )
			{
        		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_ERRORONLY )
        			IOFunctions.println( "PreDeconvolutionFusionSequential(): Subtracting background of " + Multi_View_Deconvolution.subtractBackground + " from " + view.getName() );

        		subtractBackground( view.getImage( false ), Multi_View_Deconvolution.subtractBackground );
				view.getImage( true );
			}
		}
			
		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN && isolatedWeightenerFactories.size() > 0 )
		{
			String methods = "(" + isolatedWeightenerFactories.get(0).getDescriptiveName();			
			for ( int i = 1; i < isolatedWeightenerFactories.size(); ++i )
				methods += ", " + isolatedWeightenerFactories.get(i).getDescriptiveName();			
			methods += ")";
			
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Init isolated weighteners for all views " + methods );
		}
		
		// init isolated pixel weighteners
		final AtomicInteger ai = new AtomicInteger(0);					
        Thread[] threads = SimpleMultiThreading.newThreads(conf.numberOfThreads);
        final int numThreads = threads.length;

        // compute them all in paralell ( computation done while opening )
		IsolatedPixelWeightener<?>[][] isoWinit = new IsolatedPixelWeightener<?>[ isolatedWeightenerFactories.size() ][ numViews ];
		for (int j = 0; j < isoWinit.length; j++)		
		{
			final int i = j;
			
			final IsolatedPixelWeightener<?>[][] isoW = isoWinit;
			
			for (int ithread = 0; ithread < threads.length; ++ithread)
	            threads[ithread] = new Thread(new Runnable()
	            {
	                public void run()
	                {
	                	final int myNumber = ai.getAndIncrement();
	                	
						for (int view = 0; view < numViews; view++)
							if ( view % numThreads == myNumber)
							{
								IOFunctions.println( "Computing " + isolatedWeightenerFactories.get( i ).getDescriptiveName() + " for " + views.get( view ) );
								isoW[i][view] = isolatedWeightenerFactories.get(i).createInstance( views.get(view) );
							}
	                }
	            });
			
			SimpleMultiThreading.startAndJoin( threads );
		}
		
		// test if the isolated weighteners were successfull...		
		try
		{
			boolean successful = true;
			for ( IsolatedPixelWeightener[] iso : isoWinit )
				for ( IsolatedPixelWeightener i : iso )
					if ( i == null )
						successful = false;
						
			if ( !successful )
			{
				IOFunctions.println( "Not enough memory for computing the weights for the multi-view deconvolution." );
				isoWinit = new IsolatedPixelWeightener[ 0 ][ 0 ];
			}
		}
		catch (Exception e)
		{				
			IOFunctions.println( "Not enough memory for computing the weights for the multi-view deconvolution." );
			isoWinit = new IsolatedPixelWeightener[ 0 ][ 0 ];
		}
		
		final IsolatedPixelWeightener<?>[][] isoW = isoWinit;
		
		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Computing output image (Channel " + channelIndex +  ").");

		// cache the views, imageSizes and models that we use
		final boolean useView[] = new boolean[ numViews ];
		final AbstractAffineModel3D<?> models[] = new AbstractAffineModel3D[ numViews ];
		
		for ( int i = 0; i < numViews; ++i )
		{
			useView[ i ] = Math.max( views.get( i ).getViewErrorStatistics().getNumConnectedViews(), views.get( i ).getTile().getConnectedTiles().size() ) > 0 || views.get( i ).getViewStructure().getNumViews() == 1;
			
			// if a corresponding view that was used for registration is valid, this one is too
			if ( views.get( i ).getUseForRegistration() == false )
			{
				final int angle = views.get( i ).getAcqusitionAngle();
				final int timepoint = views.get( i ).getViewStructure().getTimePoint();
				
				for ( final ViewDataBeads view2 : viewStructure.getViews() )
					if ( view2.getAcqusitionAngle() == angle && timepoint == view2.getViewStructure().getTimePoint() && view2.getUseForRegistration() == true )
						useView[ i ] = true;
			}
			
			models[ i ] = (AbstractAffineModel3D<?>)views.get( i ).getTile().getModel(); 
		}
		
		final int[][] imageSizes = new int[numViews][];		
		for ( int i = 0; i < numViews; ++i )
			imageSizes[ i ] = views.get( i ).getImageSize();
		
		ai.set( 0 );					
        threads = SimpleMultiThreading.newThreads( numThreads );

		for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(new Runnable()
            {
                public void run()
                {
                	try
                	{
                        final int myNumber = ai.getAndIncrement();

                        // temporary float array
		            	final double[] tmp = new double[ 3 ];
		            	
		        		// init combined pixel weighteners
		        		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN && combinedWeightenerFactories.size() > 0 )
		        		{
		        			String methods = "(" + combinedWeightenerFactories.get(0).getDescriptiveName();			
		        			for ( int i = 1; i < combinedWeightenerFactories.size(); ++i )
		        				methods += ", " + combinedWeightenerFactories.get(i).getDescriptiveName();			
		        			methods += ")";
		        			
		        			if ( myNumber == 0 )
		        				IOFunctions.println("Initialize combined weighteners for all views " + methods );
		        		}
		        		
		        		final CombinedPixelWeightener<?>[] combW = new CombinedPixelWeightener<?>[combinedWeightenerFactories.size()];
		        		for (int i = 0; i < combW.length; i++)
		        			combW[i] = combinedWeightenerFactories.get(i).createInstance( views );
		            	
						// get iterators for isolated weights
		        		final LocalizableByDimCursor<FloatType> isoIterators[][] = new LocalizableByDimCursor[ isoW.length ][ numViews ];            		
						for (int i = 0; i < isoW.length; i++)
							for (int view = 0; view < isoW[i].length; view++)
								isoIterators[i][view] = isoW[i][view].getResultIterator();
		        		
		    			final Point3d[] tmpCoordinates = new Point3d[ numViews ];
		    			final int[][] loc = new int[ numViews ][ 3 ];
		    			final double[][] locd = new double[ numViews ][ 3 ];
		    			final boolean[] use = new boolean[ numViews ];
		    			
		    			for ( int i = 0; i < numViews; ++i )
		    				tmpCoordinates[ i ] = new Point3d();
		    			
		    			final LocalizableCursor<FloatType> outIntensity[] = new LocalizableCursor[ numViews ];
		    			final LocalizableCursor<FloatType> outWeights[] = new LocalizableCursor[ numViews ];
		    			
		    			final float[] tmpWeights = new float[ numViews ];
		    			
		    			for ( int i = 0; i < numViews; ++i )
		    			{
		    				outIntensity[ i ] = images[ i ].createLocalizableCursor();
		    				outWeights[ i ] = weights[ i ].createLocalizableCursor();
		    			}
		    			
		    			final LocalizableCursor<FloatType> firstCursor = outIntensity[ 0 ];
		    			
		    			// create Interpolated Iterators for the input images (every thread need own ones!)
		    			final Interpolator<FloatType>[] interpolators = new Interpolator[ numViews ];
		    			for (int view = 0; view < numViews ; view++)
		    				interpolators[ view ] = views.get( view ).getImage().createInterpolator( conf.interpolatorFactorOutput );
		    			
		    			while ( firstCursor.hasNext() )
		    			{
			    			for ( int i = 0; i < numViews; ++i )
			    			{
			    				outIntensity[ i ].fwd();
			    				outWeights[ i ].fwd();
			    			}
		    				
		        			if ( firstCursor.getPosition(2) % numThreads == myNumber )
		        			{
		        				// get the coordinates if cropped (all coordinates are the same, so we only use the first cursor)
		        				final int x = firstCursor.getPosition( 0 ) + cropOffsetX;
		        				final int y = firstCursor.getPosition( 1 ) + cropOffsetY;
		        				final int z = firstCursor.getPosition( 2 ) + cropOffsetZ;

		        				// how many view contribute at this position
								int num = 0;
								for ( int i = 0; i < numViews; ++i )
								{
									if ( useView[ i ] )
									{	            							
		    							tmpCoordinates[ i ].x = x * scale + min.x;
		    							tmpCoordinates[ i ].y = y * scale + min.y;
		    							tmpCoordinates[ i ].z = z * scale + min.z;
									
		    							mpicbg.spim.mpicbg.Java3d.applyInverseInPlace( models[i], tmpCoordinates[i], tmp );
			
		    							loc[i][0] = (int)Util.round( tmpCoordinates[i].x );
		    							loc[i][1] = (int)Util.round( tmpCoordinates[i].y );
		    							loc[i][2] = (int)Util.round( tmpCoordinates[i].z );	

		    							locd[i][0] = tmpCoordinates[i].x;
		    							locd[i][1] = tmpCoordinates[i].y;
		    							locd[i][2] = tmpCoordinates[i].z;

		    							// do we hit the source image?
									if ( loc[ i ][ 0 ] >= 0 && loc[ i ][ 1 ] >= 0 && loc[ i ][ 2 ] >= 0 && 
										loc[ i ][ 0 ] < imageSizes[ i ][ 0 ] && 
										loc[ i ][ 1 ] < imageSizes[ i ][ 1 ] && 
										loc[ i ][ 2 ] < imageSizes[ i ][ 2 ] )
		    							{
		    								use[ i ] = true;
		    								++num;
		    							}	
		    							else
		    							{
		    								use[ i ] = false;
		    							}
									}
								}

								if ( num > 0 )
								{
			    						// update combined weighteners
									if ( combW.length > 0 )
										for ( final CombinedPixelWeightener<?> w : combW )
											w.updateWeights(locd, use);
		
									float sumWeights = 0;
		    						//float value = 0;
		
		    						for ( int view = 0; view < numViews; ++view )
		    						{
		    							if ( use[view] )
		    							{
		    								float weight = 1;
		    								
		    								// multiplicate combined weights
		    								if (combW.length > 0)
		    									for (final CombinedPixelWeightener<?> w : combW)
		    										weight *= w.getWeight(view);
		    								
		    								// multiplicate isolated weights
		    								for (int i = 0; i < isoW.length; i++)
		    								{
		    									isoIterators[ i ][ view ].setPosition( loc[ view ] );
												weight *= isoIterators[ i ][ view ].getType().get();
		    								}        									      
		    								
		    								tmp[ 0 ] = tmpCoordinates[view].x;
		    								tmp[ 1 ] = tmpCoordinates[view].y;
		    								tmp[ 2 ] = tmpCoordinates[view].z;
		    								
		    								interpolators[view].moveTo( tmp );
		    								
		    								if ( normalize )
		    								{
			    								sumWeights += weight;
			    								
			    								// set the intensity, remember the weight
			    								tmpWeights[ view ] = weight;
		    								}
		    								else
		    								{
		    									outWeights[ view ].getType().set( weight );
		    								}
		    								outIntensity[ view ].getType().set( interpolators[view].getType().get() );
		    							}
		    						}
		    						
		    						// set the normalized weights
		    						if ( normalize && sumWeights > 0 )
		    						{
			    						for ( int view = 0; view < numViews; ++view )
			    						{
			    							if ( use[view] )
			    							{
											if ( sumWeights > 1 )
			    									outWeights[ view ].getType().set( tmpWeights[ view ]/sumWeights );
											else
												outWeights[ view ].getType().set( tmpWeights[ view ] );
			    							}
			    						}
		    						}
		    									
	    						}
	    						
	            			} // myThread loop       				
	        			} // iterator loop
	        			
		    			for ( int i = 0; i < numViews; ++i )
		    			{
		    				outIntensity[ i ].close();
		    				outWeights[ i ].close();
	        				interpolators[ i ].close();
		    			}
		    			
	        			// close combined pixel weighteners
	        			for (int i = 0; i < combW.length; i++)
	        				combW[i].close();     
	        			
	        			// close isolated iterators
						for (int i = 0; i < isoW.length; i++)
							for (int view = 0; view < isoW[i].length; view++)
								isoIterators[i][view].close();
         			
                	}
                	catch (NoninvertibleModelException e)
                	{
                		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_ERRORONLY )
                			IOFunctions.println( "MappingFusionParalell(): Model not invertible for " + viewStructure );
                	}
                }// Thread.run loop
            });
        
        SimpleMultiThreading.startAndJoin(threads);	

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Closing all input images (Channel " + channelIndex +  ").");
		
		if ( conf.extractPSF )
		{
			// extract all PSF's
			if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
				IOFunctions.println( "Extracting all PSF's" );
			
			extractPSF.extract();
		}
		
		// unload images
		for ( final ViewDataBeads view : views ) 
			view.closeImage();
			
		// close weighteners		
		// close isolated pixel weighteners
		try
		{
			for (int i = 0; i < isoW.length; i++)
				for (int view = 0; view < numViews; view++)
					isoW[i][view].close();
		}
		catch (Exception e )
		{
			// this will fail if there was not enough memory...
		}
		
		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Done computing output image (Channel " + channelIndex +  ").");
	}

	@Override
	public Image<FloatType> getFusedImage() { return null; }
	
	@Override
	public Image<FloatType> getFusedImage( final int index ) { return images[ index ]; }
	@Override
	public Image<FloatType> getWeightImage( final int index ) { return weights[ index ]; }

	@Override
	public void closeImages() 
	{
		for ( final ViewDataBeads view : viewStructure.getViews() ) 
			view.closeImage();
	}

	@Override
	public ArrayList<Image<FloatType>> getPointSpreadFunctions() { return extractPSF.getPSFs(); }

	@Override
	public ExtractPSF getExtractPSFInstance() { return this.extractPSF; }

	@Override
	public Image<FloatType> getOverlapImage() { return overlap; }
}
