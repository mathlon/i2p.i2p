/*
 * Created on Nov 9, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  susi23@mail.i2p
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.webmail;

import i2p.susi.util.Config;
import i2p.susi.debug.Debug;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.encoding.DecodingException;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

import net.i2p.data.DataHelper;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.SystemVersion;

/**
 * data structure to hold a single message, mostly used with folder view and sorting
 * 
 * @author susi
 */
class Mail {
	
	private static final String DATEFORMAT = "date.format";
	private static final String unknown = "unknown";
	private static final String P1 = "^[^@< \t]+@[^> \t]+$";
	private static final String P2 = "^<[^@< \t]+@[^> \t]+>$";
	private static final Pattern PATTERN1 = Pattern.compile(P1);
	private static final Pattern PATTERN2 = Pattern.compile(P2);

	private int size;
	public String sender,   // as received, trimmed only, not HTML escaped
		reply, subject, dateString,
		//formattedSender,    // address only, enclosed with <>, not HTML escaped
		formattedSubject,
		formattedDate,  // US Locale, UTC
		localFormattedDate,  // Current Locale, local time zone
		shortSender,    // Either name or address but not both, HTML escaped, double-quotes removed, truncated with hellip
		shortSubject,   // HTML escaped, truncated with hellip
		quotedDate;  // Current Locale, local time zone, longer format
	public final String uidl;
	public Date date;
	private ReadBuffer header, body;
	private MailPart part;
	String[] to, cc;        // addresses only, enclosed by <>
	private boolean isNew, isSpam;
	public String contentType;
	public String messageID; // as received, trimmed only, probably enclosed with <>, not HTML escaped

	public String error;

	public boolean markForDeletion;
	
	public Mail(String uidl) {
		this.uidl = uidl;
		//formattedSender = unknown;
		formattedSubject = unknown;
		formattedDate = unknown;
		localFormattedDate = unknown;
		sender = unknown;
		shortSender = unknown;
		shortSubject = unknown;
		quotedDate = unknown;
		error = "";
	}

	/**
	 *  This may or may not contain the body also.
	 *  @return if null, nothing has been loaded yet for this UIDL
	 */
	public synchronized ReadBuffer getHeader() {
		return header;
	}

	public synchronized void setHeader(ReadBuffer rb) {
		if (rb == null)
			return;
		header = rb;
		parseHeaders();
	}

	/**
	 *  @return if false, nothing has been loaded yet for this UIDL
	 */
	public synchronized boolean hasHeader() {
		return header != null;
	}

	/**
         *  This contains the header also.
         *  @return may be null
         */
	public synchronized ReadBuffer getBody() {
		return body;
	}

	public synchronized void setBody(ReadBuffer rb) {
		if (rb == null)
			return;
		if (header == null)
			setHeader(rb);
		body = rb;
		size = rb.length;
		try {
			part = new MailPart(uidl, rb);
		} catch (DecodingException de) {
			Debug.debug(Debug.ERROR, "Decode error: " + de);
		} catch (RuntimeException e) {
			Debug.debug(Debug.ERROR, "Parse error: " + e);
		}
	}

	public synchronized boolean hasBody() {
		return body != null;
	}

	public synchronized MailPart getPart() {
		return part;
	}

	public synchronized boolean hasPart() {
		return part != null;
	}

	public synchronized int getSize() {
		return size;
	}

	public synchronized void setSize(int size) {
		if (body != null)
			return;
		this.size = size;
	}

	public synchronized boolean isSpam() {
		return isSpam;
	}

	public synchronized boolean isNew() {
		return isNew;
	}

	public synchronized void setNew(boolean isNew) {
		this.isNew = isNew;
	}

	public synchronized boolean hasAttachment() {
		// this isn't right but good enough to start
		// if part != null query parts instead?
		return contentType != null &&
			!contentType.contains("text/plain") &&
			!contentType.contains("multipart/alternative") &&
			!contentType.contains("multipart/signed");
	}

	/**
	 * 
	 * @param address E-mail address to be validated
	 * @return Is the e-mail address valid?
	 */
	public static boolean validateAddress( String address )
	{
		if( address == null || address.length() == 0 )
			return false;
		
		address = address.trim();
		
		if( address.indexOf('\n') != -1 ||
				address.indexOf('\r') != -1 )
			return false;
		
		String[] tokens = DataHelper.split(address, "[ \t]+");

		int addresses = 0;
		
		for( int i = 0; i < tokens.length; i++ ) {
			if( tokens[i].matches( "^[^@< \t]+@[^> \t]+$" ) ||
					tokens[i].matches( "^<[^@< \t]+@[^> \t]+>$" ) )
				addresses++;
		}
		return addresses == 1;
	}

	/**
	 * Returns the first email address portion, enclosed by &lt;&gt;
	 * @param address
	 */
	public static String getAddress(String address )
	{
		String[] tokens = DataHelper.split(address, "[ \t]+");

		for( int i = 0; i < tokens.length; i++ ) {
			if (PATTERN1.matcher(tokens[i]).matches())
				return "<" + tokens[i] + ">";
			if (PATTERN2.matcher(tokens[i]).matches())
				return tokens[i];
		}
		
		return null;
	}

	/**
	 * A little misnamed. Adds all addresses from the comma-separated
	 * line in text to the recipients list.
	 * 
	 * @param text comma-separated
	 * @param recipients out param
	 * @param ok will be returned
	 * @return true if ALL e-mail addresses are valid AND the in parameter was true
	 */
	public static boolean getRecipientsFromList( ArrayList<String> recipients, String text, boolean ok )
	{
		if( text != null && text.length() > 0 ) {			
			String[] ccs = DataHelper.split(text, ",");
			for( int i = 0; i < ccs.length; i++ ) {
				String recipient = ccs[i].trim();
				if( validateAddress( recipient ) ) {
					String str = getAddress( recipient );
					if( str != null && str.length() > 0 ) {
						recipients.add( str );
					}
					else {
						ok = false;
					}
				}
				else {
					ok = false;
				}
			}
		}
		return ok;
	}

	/**
	 * Adds all items from the list
	 * to the builder, separated by tabs.
	 * 
	 * @param buf out param
	 * @param prefix prepended to the addresses
	 */
	public static void appendRecipients( StringBuilder buf, ArrayList<String> recipients, String prefix )
	{
		for (int i = 0; i < recipients.size(); i++) {
			buf.append( prefix );
			prefix ="\t";
			buf.append(recipients.get(i));
			if (i < recipients.size() - 1)
				buf.append(',');
			buf.append( "\r\n" );
		}
	}

	private static final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
	private static final DateFormat localDateFormatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
	private static final DateFormat longLocalDateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
	private static final DateFormat mailDateFormatter = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH );
	static {
		// the router sets the JVM time zone to UTC but saves the original here so we can get it
		TimeZone tz = SystemVersion.getSystemTimeZone();
		localDateFormatter.setTimeZone(tz);
		longLocalDateFormatter.setTimeZone(tz);
	}

	private void parseHeaders()
	{
		error = "";
		if( header != null ) {

			boolean ok = true;
			
			Encoding html = EncodingFactory.getEncoding( "HTML" );
			
			if( html == null ) {
				error += "HTML encoder not found.\n";
				ok = false;
			}
			
			Encoding hl = EncodingFactory.getEncoding( "HEADERLINE" );

			if( hl == null ) {
				error += "Header line encoder not found.\n";
				ok = false;
			}

			if( ok ) {
				
				try {
					ReadBuffer decoded = hl.decode( header );
					BufferedReader reader = new BufferedReader( new InputStreamReader( new ByteArrayInputStream( decoded.content, decoded.offset, decoded.length ), "UTF-8" ) );
					String line;
					while( ( line = reader.readLine() ) != null ) {
						if( line.length() == 0 )
							break;

						String hlc = line.toLowerCase(Locale.US);
						if (hlc.startsWith("from:")) {
							sender = line.substring( 5 ).trim();
							//formattedSender = getAddress( sender );
							shortSender = sender.replace("\"", "").trim();
							int lt = shortSender.indexOf('<');
							if (lt > 0)
								shortSender = shortSender.substring(0, lt).trim();
							else if (lt < 0 && shortSender.contains("@"))
								shortSender = '<' + shortSender + '>';  // add missing <> (but thunderbird doesn't...)
							boolean trim = shortSender.length() > 35;
							if (trim)
								shortSender = ServletUtil.truncate(shortSender, 32).trim();
							shortSender = html.encode( shortSender );
							if (trim)
								shortSender += "&hellip;";  // must be after html encode
						}
						else if (hlc.startsWith("date:")) {
							dateString = line.substring( 5 ).trim();
							try {
								synchronized(mailDateFormatter) {
									date = mailDateFormatter.parse( dateString );
									formattedDate = dateFormatter.format( date );
									localFormattedDate = localDateFormatter.format( date );
									//quotedDate = html.encode( dateString );
									quotedDate = longLocalDateFormatter.format(date);
								}
							}
							catch (ParseException e) {
								date = null;
								e.printStackTrace();
							}
						}
						else if (hlc.startsWith("subject:")) {
							subject = line.substring( 8 ).trim();
							formattedSubject = subject;
							shortSubject = formattedSubject;
							boolean trim = formattedSubject.length() > 65;
							if (trim)
								shortSubject = ServletUtil.truncate(formattedSubject, 62).trim();
							shortSubject = html.encode( shortSubject );
							if (trim)
								shortSubject += "&hellip;";  // must be after html encode
						}
						else if (hlc.startsWith("reply-to:")) {
							reply = getAddress( line.substring( 9 ).trim() );
						}
						else if (hlc.startsWith("to:") ) {
							ArrayList<String> list = new ArrayList<String>();
							getRecipientsFromList( list, line.substring( 3 ).trim(), true );
							if (list.isEmpty()) {
								// don't set
							} else if (to == null) {
								to = list.toArray(new String[list.size()]);
							} else if (cc == null) {
								// Susimail bug before 0.9.33, sent 2nd To line that was really Cc
								cc = list.toArray(new String[list.size()]);
							} else {	
								// add to the array, shouldn't happen
								for (int i = 0; i < to.length; i++) {
									list.add(i, to[i]);
								}
								to = list.toArray(new String[list.size()]);
							}
						}
						else if (hlc.startsWith("cc:")) {
							ArrayList<String> list = new ArrayList<String>();
							getRecipientsFromList( list, line.substring( 3 ).trim(), true );
							if (list.isEmpty()) {
								// don't set
							} else if (cc == null) {
								cc = list.toArray(new String[list.size()]);
							} else {	
								// add to the array, shouldn't happen
								for (int i = 0; i < cc.length; i++) {
									list.add(i, cc[i]);
								}
								cc = list.toArray(new String[list.size()]);
							}
						} else if (hlc.equals("x-spam-flag: yes")) {
							// TODO trust.spam.headers config
							isSpam = true;
						} else if (hlc.startsWith("content-type:")) {
							// this is duplicated in MailPart but
							// we want to know if we have attachments, even if
							// we haven't fetched the body
							contentType = line.substring(13).trim();
						} else if (hlc.startsWith("message-id:")) {
							messageID = line.substring(11).trim();
						}
					}
				}
				catch( Exception e ) {
					error += "Error parsing mail header: " + e.getClass().getName() + '\n';
				}		
			}
		}
	}
}

