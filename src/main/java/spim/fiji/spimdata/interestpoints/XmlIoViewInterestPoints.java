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
package spim.fiji.spimdata.interestpoints;

import static spim.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTSFILE_TAG;
import static spim.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_LABEL_ATTRIBUTE_NAME;
import static spim.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_PARAMETERS_ATTRIBUTE_NAME;
import static spim.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_SETUP_ATTRIBUTE_NAME;
import static spim.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_TAG;
import static spim.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_TIMEPOINT_ATTRIBUTE_NAME;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;

import org.jdom2.Element;

public class XmlIoViewInterestPoints extends XmlIoSingleton< ViewInterestPoints >
{
	public XmlIoViewInterestPoints()
	{
		super( VIEWINTERESTPOINTS_TAG, ViewInterestPoints.class );
		handledTags.add( VIEWINTERESTPOINTSFILE_TAG );
	}

	public Element toXml( final ViewInterestPoints viewsInterestPoints )
	{
		final Element elem = super.toXml();

		// sort all entries by timepoint and viewsetupid so that it is possible to edit XML by hand
		final ArrayList< ViewInterestPointLists > viewIPlist = new ArrayList< ViewInterestPointLists >();
		viewIPlist.addAll( viewsInterestPoints.getViewInterestPoints().values() );
		Collections.sort( viewIPlist );

		for ( final ViewInterestPointLists v : viewIPlist )
		{
			// sort all entries by label so that it is possible to edit XML by hand
			final ArrayList< String > labelList = new ArrayList< String >();
			labelList.addAll( v.getHashMap().keySet() );
			Collections.sort( labelList );

			for ( final String label : labelList )
			{
				final InterestPointList list = v.getInterestPointList( label );
				elem.addContent( viewInterestPointsToXml( list, v.getTimePointId(), v.getViewSetupId(), label ) );
			}
		}
		return elem;
	}

	public ViewInterestPoints fromXml( final Element allInterestPointLists, final File basePath, final Map< ViewId, ViewDescription > viewDescriptions ) throws SpimDataException
	{
		final ViewInterestPoints viewsInterestPoints = super.fromXml( allInterestPointLists );
		viewsInterestPoints.createViewInterestPoints( viewDescriptions );

		for ( final Element viewInterestPointsElement : allInterestPointLists.getChildren( VIEWINTERESTPOINTSFILE_TAG ) )
		{
			final int timepointId = Integer.parseInt( viewInterestPointsElement.getAttributeValue( VIEWINTERESTPOINTS_TIMEPOINT_ATTRIBUTE_NAME ) );
			final int setupId = Integer.parseInt( viewInterestPointsElement.getAttributeValue( VIEWINTERESTPOINTS_SETUP_ATTRIBUTE_NAME ) );
			final String label = viewInterestPointsElement.getAttributeValue( VIEWINTERESTPOINTS_LABEL_ATTRIBUTE_NAME );
			final String parameters = viewInterestPointsElement.getAttributeValue( VIEWINTERESTPOINTS_PARAMETERS_ATTRIBUTE_NAME );

			final String interestPointFileName = viewInterestPointsElement.getTextTrim();

			final ViewId viewId = new ViewId( timepointId, setupId );
			final ViewInterestPointLists collection = viewsInterestPoints.getViewInterestPointLists( viewId );

			// we do not load the interestpoints nor the correspondinginterestpoints, we just do that once it is requested
			final InterestPointList list = new InterestPointList( basePath, new File( interestPointFileName ) );
			list.setParameters( parameters );
			collection.addInterestPointList( label, list );
		}

		return viewsInterestPoints;
	}

	protected Element viewInterestPointsToXml( final InterestPointList interestPointList, final int tpId, final int viewId, final String label )
	{
		final Element elem = new Element( VIEWINTERESTPOINTSFILE_TAG );
		elem.setAttribute( VIEWINTERESTPOINTS_TIMEPOINT_ATTRIBUTE_NAME, Integer.toString( tpId ) );
		elem.setAttribute( VIEWINTERESTPOINTS_SETUP_ATTRIBUTE_NAME, Integer.toString( viewId ) );
		elem.setAttribute( VIEWINTERESTPOINTS_LABEL_ATTRIBUTE_NAME, label );
		elem.setAttribute( VIEWINTERESTPOINTS_PARAMETERS_ATTRIBUTE_NAME, interestPointList.getParameters() );
		// a hack so that windows does not put its backslashes in
		elem.setText( interestPointList.getFile().toString().replace( "\\", "/" ) );

		return elem;
	}
}
