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
package fiji.plugin.timelapsedisplay;

import java.io.File;
import java.util.ArrayList;

import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;
import mpicbg.spim.registration.bead.Bead;

public class RegistrationStatistics
{
	double minError = 0;
	double avgError = 0;
	double maxError = 0;

	double minRatio = 1;
	double maxRatio = 0;
	double avgRatio = 0;

	final int timePoint;
	File worstView;
	
	/**
	 * Call this class after a registration is performed and it will collect the
	 * information it wants
	 *
	 * @param viewStructure - the current {@link ViewStructure}
	 */
	public RegistrationStatistics( final ViewStructure viewStructure )
	{
		this.timePoint = viewStructure.getTimePoint();

		collect( viewStructure );
	}

	public RegistrationStatistics( final int timePoint, final double minError, final double avgError, final double maxError, final double minRatio, final double avgRatio, final double maxRatio,
								   final File worstView )
	{
		this( timePoint, minError, avgError, maxError, minRatio, avgRatio, maxRatio );
		this.worstView = worstView;		
	}

	public RegistrationStatistics( final int timePoint, final double minError, final double avgError, final double maxError, final double minRatio, final double avgRatio, final double maxRatio )
	{
		this.timePoint = timePoint;
		this.minError = minError;
		this.avgError = avgError;
		this.maxError = maxError;
		this.minRatio = minRatio;
		this.avgRatio = avgRatio;
		this.maxRatio = maxRatio;
	}

	final int getTimePoint() { return timePoint; }
	final double getMinError() { return minError; }
	final double getAvgError() { return avgError; }
	final double getMaxError() { return maxError; }
	final double getMinRatio() { return minRatio; }
	final double getAvgRatio() { return avgRatio; }
	final double getMaxRatio() { return maxRatio; }

	protected void collect( final ViewStructure viewStructure )
	{
		minError = viewStructure.getGlobalErrorStatistics().getMinAlignmentError();
		avgError = viewStructure.getGlobalErrorStatistics().getAverageAlignmentError();
		maxError = viewStructure.getGlobalErrorStatistics().getMaxAlignmentError();

		minRatio = 1;
		maxRatio = 0;
		avgRatio = 0;
		int numViews = 0;
		ViewDataBeads worstView = viewStructure.getViews().get( 0 );

		for ( final ViewDataBeads view : viewStructure.getViews() )
		{
			if ( view.getUseForRegistration() )
			{
				final ArrayList<Bead> beadList = view.getBeadStructure().getBeadList();

				int candidates = 0;
				int correspondences = 0;

				for ( final Bead bead : beadList )
				{
					candidates += bead.getDescriptorCorrespondence().size();
					correspondences += bead.getRANSACCorrespondence().size();
				}

				if ( candidates > 0 )
				{
					final double ratio = (double)correspondences / (double)candidates;

					if ( ratio <= minRatio )
					{
						minRatio = ratio;
						worstView = view;
					}

					if ( ratio > maxRatio )
						maxRatio = ratio;

					avgRatio += ratio;
				}
				else
				{
					minRatio = 0;
				}
				numViews++;
			}
		}

		avgRatio /= (float)numViews;
		
		this.worstView = new File( worstView.getFileName() );
		// System.out.println("data.add( new TimepointData( "+timepoint.getTimePoint()+", "+minError+", "+avgError+", "+maxError+", "+minRatio+", "+avgRatio+", "+maxRatio+" ) );");
	}
}
