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
package mpicbg.spim.io;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import mpicbg.spim.registration.bead.Bead;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class XMLFunctions
{
	public static void main( String args[] )
	{
		parseXMLBeadFile( "D:/Temp/HisYFP/registration/beads.xml", "View2", 1, "D:/Temp/HisYFP/registration/" );
	}
	
	public static void parseXMLBeadFile( final String xmlFile, final String viewName, final int viewID, final String outputDirectory )
	{
		//
		// Parse the XML file
		//
		final Document dom = parseXMLFile( xmlFile );
		
		final ArrayList<Bead> beads = parseBeadXMLDocument( dom, viewName );		
		
		//
		// write beads txt file
		//
		IOFunctions.writeSegmentation( beads, null, viewName, viewID, outputDirectory );
	}

	protected static ArrayList<Bead> parseBeadXMLDocument( final Document dom, final String viewName )
	{
		final ArrayList<Bead> beads = new ArrayList<Bead>();
		
		//get the root elememt
		Element docEle = dom.getDocumentElement();
		
		//get a nodelist of <employee> elements
		NodeList nl = docEle.getElementsByTagName( viewName );

		// parse all the beads
		final Element viewElement = (Element)nl.item( 0 );

		// get the list of Points
		final NodeList pointList = viewElement.getElementsByTagName( "Points" );		
		final Element points = (Element)pointList.item( 0 );
		
		final NodeList beadList = points.getChildNodes();

		int beadIndex = 0;
		
		if( beadList != null && beadList.getLength() > 0 ) 
		{
			for( int i = 0 ; i < beadList.getLength(); ++i ) 
			{	
				final Node a = beadList.item(i);
								
				if ( a.getNodeType() == 1 )
				{
					//get the bead element
					final Element beadElement = (Element)a;
										
					//get the Bead object
					final Bead bead = getBead( beadElement, beadIndex++ ); 
					
					//add it to list
					beads.add( bead );
				}
			}
		}
		
		System.out.println( "Found " + beads.size() + " for " + viewName );
		
		return beads;
	}
	
	/**
	 * I take a bead element and read the values in, create
	 * an Bead object and return it
	 * @param beadElement
	 * @return {@link Bead}
	 */
	protected static Bead getBead( final Element beadElement, final int id ) 
	{
		//for each <p1>...<pn> element get double values of x,y,z 
		final double x = getDoubleValue( beadElement ,"x");
		final double y = getDoubleValue( beadElement ,"y");
		final double z = getDoubleValue( beadElement ,"z");
		
		//Create a new Employee with the value read from the xml nodes
		final Bead e = new Bead( id, new double[]{ x, y, z }, null );

		return e;
	}

	/**
	 * I take a xml element and the tag name, look for the tag and get
	 * the text content 
	 * i.e for &gt;employee&lt;&gt;name&lt;John&gt;/name&lt;&gt;/employee&lt; xml snippet if
	 * the Element points to employee node and tagName is name I will return John  
	 * @param ele
	 * @param tagName
	 * @return
	 */
	protected static String getTextValue(Element ele, String tagName) 
	{
		String textVal = null;
		NodeList nl = ele.getElementsByTagName(tagName);
		
		if( nl != null && nl.getLength() > 0 ) 
		{
			Element el = (Element)nl.item(0);
			textVal = el.getFirstChild().getNodeValue();
		}

		return textVal;
	}

	
	/**
	 * Calls getTextValue and returns a int value
	 * @param ele
	 * @param tagName
	 * @return
	 */
	protected static double getDoubleValue( final Element ele, final String tagName) 
	{
		//in production application you would catch the exception
		return Double.parseDouble( getTextValue( ele, tagName ) );
	}
	
	public static Document parseXMLFile( final String xmlFile )
	{
		Document dom = null;
		
		//get the factory
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		try {

			//Using factory get an instance of document builder
			DocumentBuilder db = dbf.newDocumentBuilder();

			//parse using builder to get DOM representation of the XML file
			dom = db.parse( xmlFile );
		}
		catch(ParserConfigurationException pce) 
		{
			pce.printStackTrace();
		}
		catch(SAXException se) 
		{
			se.printStackTrace();
		}
		catch(IOException ioe) 
		{
			ioe.printStackTrace();
		} 

		return dom;
	}
}
