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
package spim.process.fusion.weightedavg;

import java.util.ArrayList;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import spim.process.fusion.FusionHelper;
import spim.process.fusion.ImagePortion;
import spim.process.fusion.boundingbox.BoundingBoxGUI;

/**
 * Fuse one portion of a paralell fusion, supports one weight function
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 * @param <T>
 */
public class ProcessParalellPortionWeight< T extends RealType< T > > extends ProcessParalellPortion< T >
{
	final ArrayList< RealRandomAccessible< FloatType > > weights;
	
	public ProcessParalellPortionWeight(
			final ImagePortion portion,
			final ArrayList< RandomAccessibleInterval< T > > imgs,
			final ArrayList< RealRandomAccessible< FloatType > > weights,
			final InterpolatorFactory< T, RandomAccessible< T > > interpolatorFactory,
			final AffineTransform3D[] transforms,
			final Img< T > fusedImg,
			final BoundingBoxGUI bb )
	{
		super( portion, imgs, interpolatorFactory, transforms, fusedImg, bb );
		
		this.weights = weights;
	}

	@Override
	public String call() throws Exception 
	{
		final int numViews = imgs.size();
		
		// make the interpolators, weights and get the transformations
		final ArrayList< RealRandomAccess< T > > interpolators = new ArrayList< RealRandomAccess< T > >( numViews );
		final ArrayList< RealRandomAccess< FloatType > > weightAccess = new ArrayList< RealRandomAccess< FloatType > >();
		final int[][] imgSizes = new int[ numViews ][ 3 ];
		
		for ( int i = 0; i < numViews; ++i )
		{
			final RandomAccessibleInterval< T > img = imgs.get( i );
			imgSizes[ i ] = new int[]{ (int)img.dimension( 0 ), (int)img.dimension( 1 ), (int)img.dimension( 2 ) };
			
			interpolators.add( Views.interpolate( Views.extendMirrorSingle( img ), interpolatorFactory ).realRandomAccess() );
						
			weightAccess.add( weights.get( i ).realRandomAccess() );
		}

		final Cursor< T > cursor = fusedImg.localizingCursor();
		final float[] s = new float[ 3 ];
		final float[] t = new float[ 3 ];
		
		cursor.jumpFwd( portion.getStartPosition() );
		
		for ( int j = 0; j < portion.getLoopSize(); ++j )
		{
			// move img cursor forward any get the value (saves one access)
			final T v = cursor.next();
			cursor.localize( s );
			
			if ( doDownSampling )
			{
				s[ 0 ] *= downSampling;
				s[ 1 ] *= downSampling;
				s[ 2 ] *= downSampling;
			}
			
			s[ 0 ] += bb.min( 0 );
			s[ 1 ] += bb.min( 1 );
			s[ 2 ] += bb.min( 2 );
			
			double sum = 0;
			double sumW = 0;
			
			for ( int i = 0; i < numViews; ++i )
			{				
				transforms[ i ].applyInverse( t, s );
				
				if ( FusionHelper.intersects( t[ 0 ], t[ 1 ], t[ 2 ], imgSizes[ i ][ 0 ], imgSizes[ i ][ 1 ], imgSizes[ i ][ 2 ] ) )
				{
					final RealRandomAccess< T > r = interpolators.get( i );
					r.setPosition( t );
					
					final RealRandomAccess< FloatType > weight = weightAccess.get( i );
					weight.setPosition( t );
					
					final double w = weight.get().get();
					
					sum += r.get().getRealDouble() * w;
					sumW += w;
				}
			}
			
			if ( sumW > 0 )
				v.setReal( sum / sumW );
		}
		
		return portion + " finished successfully (one weight).";
	}

}
