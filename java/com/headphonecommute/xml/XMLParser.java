package com.headphonecommute.xml;

import java.io.File;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.PriorityQueue;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class XMLParser
{
  private static int YEAR;
	
	private final static int MAX_2COL_RESULTS = 10;
	private final static int MAX_3COL_RESULTS = 99;
	
	private final static PriorityQueue< Item >  queue 			= new PriorityQueue< Item >( 128 * 4, new ItemComparator() );
	
	private final static ArrayList< Item > tempList				= new ArrayList< Item >();
	
	private static class Item
	{
		boolean isReview		= false;
		boolean isImage			= false;
		boolean isLargeImage	= false;
		
		String title;
		String link;
		String guid;
		String content;
		String pubDate;

		String albumTitle;
		String artist;
		String label;
		String imageUrl;
		
		Date date;
		
		// Debug output
		public String toString()
		{
			return 	  "Title==[" + albumTitle + "]\n" 
					+ "Artist=[" + artist + "]\n"
					+ "Label==[" + label + "]\n"
					+ "Link===[" + link + "]\n"
					+ "Image==[" + imageUrl + "]\n"
					+ "Date===[" + date + "]\n";
		}
	}
	

	public static class ItemComparator implements Comparator< Item >
	{
		@Override
		public int compare( Item o1, Item o2 )
		{
			return o2.date.compareTo( o1.date );
		}		
	}
	
	
	
	private static void parseXML( File file ) throws Exception
	{
		DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

		Document doc = builder.parse( file );
		
		// Skip ahead to a sibling titled "rss" (past the comments)
		Node rss = skipToSibling( doc.getFirstChild(), "rss" );
		
		// "And now find the "channel" child within it
		Node channel = skipToChild( rss, "channel" );
		
		
		NodeList list = channel.getChildNodes();

		for ( int i = 0; i < list.getLength(); i++ )
		{
			Node node = list.item( i );
			
			if ( node.getNodeName().equalsIgnoreCase( "item" ) )
			{
				Item item = onItem( node );
				
				additionalParsing( item );
				
				if ( item.isReview && ! item.isImage )
				{
					queue.add( item );
				}
			}
		}
		
		generateHtml();
		
	}
	
	
	private static Item onItem( Node itemNode )
	{
		Item item = new Item();
		
		NodeList list = itemNode.getChildNodes();
		
		for ( int i = 0; i < list.getLength(); i ++ )
		{
			Node node = list.item( i );
			
			if ( node.getNodeName().equalsIgnoreCase( "title" ) )	item.title = node.getTextContent();
			if ( node.getNodeName().equalsIgnoreCase( "link" ) )	item.link = node.getTextContent();	
			if ( node.getNodeName().equalsIgnoreCase( "guid" ) ) 	item.guid = node.getTextContent();
			if ( node.getNodeName().equalsIgnoreCase( "pubdate" ) )	item.pubDate = node.getTextContent();
			
			// The content:encoded node has a subnode with text, just rip that out directly
			if ( node.getNodeName().equalsIgnoreCase( "content:encoded" ) ) item.content = node.getFirstChild().getTextContent();
		}
		
		return item;		
	}
	
	
	private static void additionalParsing( Item item )
	{
		detectImage( item );
		
		if ( item.isImage ) return;
		
		try 
		{
			parseTitle( item );
			item.isReview = true;
		}
		catch ( StringIndexOutOfBoundsException e )
		{
			// Couldn't detect delimiters - must not be a properly titled review.
		}
		
		if ( ! item.isReview ) return;
		
		locateImageURL( item );

		// Clear out the image content. We don't need it anymore (let GC save space in object)
		item.content = null;
		
		try
		{
			parseDate( item );
		}
		catch ( ParseException e )
		{
			// Couldn't parse the Date.
		}
	}
	
	
	// Special parsing where the Title of an Item contains information in a special format:
	// Album Title - Artist (Label)
	private static void parseTitle( Item item ) throws StringIndexOutOfBoundsException
	{
		String text = item.title;

		// Walk backwards to extract the label first and make sure there are no 
		// parenthesis in the actual title of the album
		int lastParenthesis = text.lastIndexOf( ")" );
		int firstParenthesis = text.lastIndexOf( "(" );
		item.label = text.substring( firstParenthesis + 1, lastParenthesis );
		
		// Now walk from beginning, in case there is a dash in the album title
		text = text.substring( 0, firstParenthesis - 1 );
		int dashLocation = text.indexOf( "-" );
		
		// Hmm. Maybe we should try for a longer dash
		if ( dashLocation == -1 ) dashLocation = text.indexOf( "–" );
		
		item.artist 	= text.substring( 0, dashLocation - 1 );
		item.albumTitle = text.substring( dashLocation + 2, text.length() );
	}
	
	
	// Some Items in the XML are actually links to images. See if this is one of them
	private static void detectImage( Item item )
	{
		if ( item.guid.lastIndexOf( ".jpg" ) > 0 ) item.isImage = true;
		if ( item.guid.lastIndexOf( ".jpeg" ) > 0 ) item.isImage = true;
	}
	
	
	// Locate the URL to the embedded image within the Review
	private static void locateImageURL( Item item )
	{
		String text = item.content;
		
		// Locate first Image Bracket
		int firstClosedBracket = text.indexOf( ">" );
		if ( firstClosedBracket == -1 ) return;
		
		text = text.substring( 0, firstClosedBracket );
		
		// Make sure this is the image with the right 500x500 dimensions
		item.isLargeImage = text.indexOf( "width=\"500\"" ) > -1;

		// OK! Find the source now!
		int srcIndex = text.indexOf( "src=\"" );
		if ( srcIndex == -1 ) return;
		
		text = text.substring( srcIndex + 5, text.length() );
		int closedQuoteIndex = text.indexOf( "\"" );
		if ( closedQuoteIndex == -1 ) return;
		
		String imageUrl = text.substring( 0, closedQuoteIndex );

		item.imageUrl = imageUrl;
	}
	
	
	private final static DateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
	private static void parseDate( Item item ) throws ParseException
	{
		item.date = formatter.parse( item.pubDate );
	}
	
	
	private static void generateHtml()
	{
		write(  "<h2>Review Archive " + YEAR + "</h2>\n<div class=\"mainpage\">\n");
		
		generateColumns( 2 );
		
		generateColumns( 3 );
		
		generateTextBullets();
		
		write(  "</div>\n");
	}

	
	@SuppressWarnings ( "deprecation")
	private static void generateColumns( int numColumns )
	{
		Item item;
		
		tempList.clear();
		
		generateTableHeader();
		
		int maxResults = ( numColumns == 2 ) ? MAX_2COL_RESULTS : MAX_3COL_RESULTS;
		
		int count = 0;
		while ( ( item = queue.poll() ) != null )
		{
			// Only process items for the given year
			if ( item.date.getYear() != YEAR - 1900 ) continue;
			
			
			// Skip smaller images for a two column generation
			if ( numColumns == 2 && !item.isLargeImage ) 
			{
				tempList.add( item );
				continue;
			}

			
			if ( item.imageUrl != null )
			{
				if ( numColumns == 2 ) generateTwoColumns( item, count % 2 );
				if ( numColumns == 3 ) generateThreeColumns( item, count % 3 );
				count++;
			}
			else
			{
				tempList.add( item );
			}
			
			
			if ( count == maxResults ) break;
		}
		
		generateTableFooter();		
		
		
		// Throw all the items we couldn't process back into the queue
		queue.addAll( tempList );
	}
	
	
	
	private static void generateTextBullets()
	{
		if ( queue.size() == 0 ) return; 
		
		
		Item item;
		write(  "<ul>");
		while ( ( item = queue.poll() ) != null )
		{
			generateBulletItem( item );
		}
		write(  "</ul>");
	}
	
	
	
	// ------------------------------------------------------------------------------------------
	// HTML OUTPUT
	// ------------------------------------------------------------------------------------------
	
	
	private static void generateTableHeader()
	{
		write(  "<table style=\"border:0;margin:0;padding:0;\" width=\"100%\" border=\"0\">\n");
		write(  "<tbody><tr><td style=\"border:0;padding:0;\" height=\"20\"></td></tr><tr><td style=\"border:0;padding:0;\">\n");
		write(  "<table style=\"border:0;margin:0;padding:0;\" width=\"100%\"><tbody>\n");
	}
	
	
	private static void generateTableFooter()
	{
		write(  "</tbody></table></td></tr><tr><td style=\"border:0;padding:0;\" height=\"20\"></td></tr></tbody></table>\n");
	}
	
	
	private static void generateTwoColumns( Item item, int column )
	{
		
		if ( column == 0 )
		{
			write(  "<tr>\n" );
		}
		
		write( "<td style=\"border:0;padding:0;\" valign=\"top\" width=\"45%\">");
		write( "<a href=\"" + item.link + "\" ");
		write( "title=\""+ item.title + "\">");
		write( "<img src=\"" + item.imageUrl + "?w=215\" ");
		write( "alt=\"" + item.title + "\" width=\"215\" height=\"215\" class=\"alignnone size-medium\" />\n");
		write( "<span style=\"font-size:small;\"><strong>" + item.artist + "</strong></span>\n");
		write( "<span style=\"font-size:small;\"><em>" + item.albumTitle + "</em></span>\n");
		write( "<span style=\"font-size:x-small;\">" + item.label + "</span></a></td>\n");
		
		
		if ( column == 0 )
		{
			// Column Separator
			write(  "<td style=\"border:0;padding:0;\" width=\"10%\"></td>\n" );
		}
		else
		{
			// End of Row
			write(  "</tr><tr><td style=\"border:0;padding:0;\" height=\"20\"></td></tr>\n" );
		}
			
	}

	
	private static void generateThreeColumns( Item item, int column )
	{

		if ( column == 0 )
		{
			write(  "<tr>\n" );
		}
		
		write( "<td style=\"border:0;padding:0;\" valign=\"top\" width=\"32%\">");
		write( "<a href=\"" + item.link + "\"");
		write( " title=\""+ item.title + "\">");
		write( "<img src=\"" + item.imageUrl + "?w=150\" ");
		write( " alt=\"" + item.title + "\" width=\"150\" height=\"150\" class=\"alignnone size-small\" />\n");
		write( "<span style=\"font-size:x-small;\"><strong>" + item.artist + "</strong></span>\n");
		write( "<span style=\"font-size:x-small;\"><em>" + item.albumTitle + "</em></span>\n");
		write( "<span style=\"font-size:x-small;\">" + item.label + "</span></a></td>\n");
		
		if ( column == 0 || column == 1 )
		{
			write(  "<td style=\"border:0;padding:0;\" width=\"2%\"></td>\n" );
		}
		else if ( column == 2)
		{
			// End of Row
			write(  "</tr><tr><td style=\"border:0;padding:0;\" height=\"20\"></td></tr>\n" );
		}
			
	}
	
	
	private static void generateBulletItem( Item item )
	{
		write( "<li><a href=\"" + item.link + "\"");
		write( " title=\""+ item.title + "\">" + item.title + "</a></li>\n");
	}
	
	
	
	// ------------------------------------------------------------------------------------------
	// --- OUTPUT
	// ------------------------------------------------------------------------------------------
	
	private static void write(  String string )
	{
		// This could be changed to a file instead of standard out
		System.out.print( string );
	}
	
	
	// ------------------------------------------------------------------------------------------
	// --- NODE WALKING UTILITIES
	// ------------------------------------------------------------------------------------------
	
	private static Node skipToChild( Node head, String name )
	{
		Node node = null;
		
		NodeList nodeList = head.getChildNodes();
		
		for ( int i = 0; i < nodeList.getLength(); i++ )
		{
			node = nodeList.item( i );
			
			if ( node.getNodeName().equalsIgnoreCase( name ) ) break; 
		}
		
		return node;
	}
	
	
	private static Node skipToSibling( Node node, String name )
	{
		while ( ! node.getNodeName().equalsIgnoreCase( name ) ) 
		{
			node = node.getNextSibling(); 
		}
		
		return node;
	}
	
	
	
	
	public static void main( String[] args )
	{
		if ( args.length == 1 )
		{
			YEAR = Integer.parseInt( args[ 0 ] );
		}
		else
		{
			System.out.println( "Must specify YEAR for which to generate the output");
			return;
		}
		
		// Note: Use Export to generate full XML file
		String xmlFile = "/home/mlazarev/Downloads/xml/headphonecommute.wordpress.2013-06-12.xml";
		
		File file = new File( xmlFile );
		
		try 
		{
			parseXML( file );
		}
		catch ( Exception e )
		{
			e.printStackTrace();
		}
	}
}
