package com.esri.json.hadoop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.RecordReader;

/**
 * 
 * Enumerates records from an Esri Unenclosed JSON file
 * 
 */
public class UnenclosedJsonRecordReader implements RecordReader<LongWritable, Text>{
	static final Log LOG = LogFactory.getLog(UnenclosedJsonRecordReader.class.getName());
	
	private BufferedReader inputReader;
	private FileSplit fileSplit;
	
	
	long readerPosition;
	long start, end;
	private boolean firstBraceConsumed = false;
	
	public UnenclosedJsonRecordReader(InputSplit split, Configuration conf) throws IOException
	{
		fileSplit = (FileSplit)split;
		
		Path filePath = fileSplit.getPath();
		
		start = fileSplit.getStart();
		end = fileSplit.getLength() + start;
		
		readerPosition = start;

		FileSystem fs = filePath.getFileSystem(conf);
		
		inputReader = new BufferedReader(new InputStreamReader(fs.open(filePath)));
		
		if (start != 0) {
			// split starts inside the json
			inputReader.skip(start);
			moveToRecordStart();
		}
	}
	
	@Override
	public void close() throws IOException {
		if (inputReader != null)
			inputReader.close();
	}

	@Override
	public LongWritable createKey() {
		return new LongWritable();
	}

	@Override
	public Text createValue() {
		return new Text();
	}

	private int getChar() throws IOException {
		int ch = inputReader.read();
		readerPosition++;
		return ch;
	}

	private int getNonWhite() throws IOException {
		int ch;
		do {
			ch = getChar();
		} while (Character.isWhitespace((char)ch));
		return ch;
	}

	@Override
	public long getPos() throws IOException {
		return readerPosition;
	}

	@Override
	public float getProgress() throws IOException {
		return (float)(readerPosition-start)/(end-start);
	}

	/**
	 * Given an arbitrary byte offset into a unenclosed JSON document, 
	 * find the start of the next record in the document.  Discard trailing
	 * bytes from the previous record if we happened to seek to the middle
	 * of it
	 * 
	 * Record boundary defined as : \{\s*"(attributes|geometry)"\s*:\s*\{
	 * 
	 * @throws IOException
	 */
	private boolean moveToRecordStart() throws IOException {
		int next = 0;
		long resetPosition = readerPosition;

		// The case of split point exactly at whitespace between records, is
		// handled by forcing it to the split following, in the interest of
		// better balancing the splits, by consuming the whitespace in next().
		// The alternative of forcing it to the split preceding, could be
		// done like what is commented here.
		//   while (next != '{' || skipDup > 0) {  // skipDup>0 => record already consumed
		// 	  next = getChar();
		// 	  if (next < 0)  return false;   // end of stream, no good
		// 	  if (next == '}')  skipDup = -1;  // Definitely not
		// 	  else if (skipDup == 0) skipDup = 1;  // no info - Maybe so until refuted by '}'
		//   }

		while (true) {

			// scan until we reach a {
			while (next != '{') {
				next = getChar();
				
				// end of stream, no good
				if (next < 0) {
					return false;
				}
			}
			
			resetPosition = readerPosition;
			inputReader.mark(100);
			
			// ok last char was '{', skip till we get to a '"'
			next = getNonWhite();
			if (next < 0) {   // end of stream, no good
				return false;
			}
			if (next != '"') {
				continue;
			}

			boolean inEscape = false;
			String fieldName = "";
			// Next should be a field name of  attributes  or  geometry .

            // If we see another opening brace, the previous one must have been inside
            // a quoted string literal (after which the double quote we found, was a
			// closing quote mark rather than the opening quote mark) - start over.

			while (next != '{') {
				next = getChar();
				if (next < 0) {  // end of stream, no good
					return false;
				}

				inEscape = (!inEscape && next == '\\');
				if (!inEscape && next == '"') {
					break;
				}

				fieldName += (char)next;
			}
			
			if (!(fieldName.equals("attributes") || fieldName.equals("geometry"))) {
				// not the field name we were expecting, start over
				continue;
			}
			
			// ok last char was '"', skip till we get to a ':'
			next = getNonWhite();
			if (next < 0) {   // end of stream, no good
				return false;
			}
			if (next != ':') {
				continue;
			}
			
			// and finally, if the next char is a {, we know for sure that this is a valid record
			next = getNonWhite();
			if (next < 0) {   // end of stream, no good
				return false;
			}
			
			if (next == '{') {
				// at this point we can be sure that we have found the record boundary
				break;
			}
		}
		
		inputReader.reset();
		readerPosition = resetPosition;
		
		firstBraceConsumed = true;
		
		return true;
	}

	@Override
	public boolean next(LongWritable key, Text value) throws IOException {
		/*
		 * NOTE : we are not using a JSONParser, so this will not validate JSON structure aside from correct counts of '{' and '}'
		 * The fact that it may handle some invalid JSON, does not imply that we support invalid JSON.
		 * 
		 * The JSON will look like this (white-space ignored)
		 * 
		 * { // start record 1
		 * 	"attributes" : {}
		 *  "geometry" : {}
		 * } // end record 1
		 * { // start record 2
		 * 	"attributes" : {}
		 *  "geometry" : {}
		 * } // end record 2
		 * 
		 * We will count '{' and '}' to find the beginning and end of each record, while ignoring braces in string literals
		 */
		
		int chr = 0;
		int brace_depth = 0;
		char lit_char = 0;
		boolean first_brace_found = false;

		// The case of split point exactly at whitespace between records,
		// is handled by forcing the record following to the split following,
		// in the interest of better balancing the splits, by consuming the
		// whitespace before checking the end of the split.
		if (!firstBraceConsumed) {  // That should only ever be true on the very first read in the split
			chr = getNonWhite();
			firstBraceConsumed = (chr == '{');
		}

		if ( readerPosition + (firstBraceConsumed ? 0 : 1)  >  end )  {
			return false;
		}
		
		StringBuilder sb = new StringBuilder(2000);
		
		if (firstBraceConsumed) {
			// first open brace was consumed already;
			// update initial state accordingly
			brace_depth = 1;
			sb.append("{");
			first_brace_found = true;
			firstBraceConsumed = false;
			key.set(readerPosition - 1);
		}
		
		boolean inEscape = false;
		while (brace_depth > 0 || !first_brace_found)
		{
			chr = getChar();
			
			if (chr < 0) {
				if (first_brace_found){
					// last record was invalid
					LOG.error("Parsing error : EOF occured before record ended");
				}
				return false;
			}
			
			switch (chr)
			{
			case '\\':
				inEscape = (lit_char != 0 && !inEscape);
				break;
			case '"':
			case '\'':
				if (lit_char == 0) {
					lit_char = (char) chr;  // mark start literal (double/single quote)
				}
				else if (inEscape) {
					inEscape = false;
				}
				else if (lit_char == chr) {
					lit_char = 0;   // mark end literal (double/single-quote)
				}
 				// ignored because we found a ' inside a " " block quote (or vice versa)
				break;
			case '{':
				if (inEscape) {
					inEscape = false;
				}
				else if (lit_char == 0) {  // not in string literal,
					brace_depth++;         // so increase brace depth
					if (!first_brace_found) {
						first_brace_found = true;
						key.set(readerPosition - 1); // set record key to the char offset of the first '{'
					}
				}
				break;
			case '}':
				if (inEscape) {
					inEscape = false;
				}
				else if (lit_char == 0) { // not in string literal,
					brace_depth--;  //  so decrease brace depth
				}
				break;
			default:
				inEscape = false;
				break;
			}
			
			if (brace_depth < 0){
				// found more '}'s than we did '{'s
				LOG.error("Parsing error : no '{' - unmatched '}' in record");
				return false;
			}
			
			if (first_brace_found){
				sb.append((char)chr);
			}
		}
		
		// no '{' found before EOF.  Not an error as this could mean that there is extra white-space at the end
		if (!first_brace_found){
			return false;
		}
		
		value.set(sb.toString());
		return true;
	}

}
